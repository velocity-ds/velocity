#!/usr/local/bin/python3
"""Trusted PID 1. Never import guest modules or execute guest code as this UID.

The root supervisor retains SETUID, SETGID, KILL and DAC_READ_SEARCH. Guest UID 65532
has no capabilities. Storage stays mounted until collection/cleanup, with a
hard idle/collection TTL even if the calling JVM disappears.
"""

import base64
import ctypes
import errno
import json
import os
import re
import selectors
import signal
import socket
import stat
import subprocess
import sys
import time

ROOT = "/work"
CONTROL = ROOT + "/control"
SOCKET = CONTROL + "/runner.sock"
UID = 65532
MAX_STREAM = 65536
MAX_INPUT = 25 * 1024 * 1024
MAX_FILE = 10 * 1024 * 1024
MAX_OUTPUT = 25 * 1024 * 1024
MAX_REQUEST = 36 * 1024 * 1024
PROFILE = "velocity-python-v3"
SAFE = re.compile(r"(?:[A-Za-z0-9][A-Za-z0-9._-]{0,98}[A-Za-z0-9_-]|[A-Za-z0-9])\Z")
RESERVED = {"CON", "PRN", "AUX", "NUL", *[f"COM{i}" for i in range(10)], *[f"LPT{i}" for i in range(10)]}


def filename(value):
    if (
        not isinstance(value, str)
        or ".." in value
        or not SAFE.fullmatch(value)
        or value.split(".")[0].upper() in RESERVED
    ):
        raise ValueError("invalid_filename")
    return value


def execution_seconds(request):
    millis = request["execution_millis"]
    epoch_millis = request["deadline_epoch_millis"]
    if type(millis) is not int or millis < 1 or type(epoch_millis) is not int or epoch_millis < 1:
        raise ValueError("invalid_limits")
    remaining = min(millis / 1000, epoch_millis / 1000 - time.time())
    if remaining <= 0:
        raise ValueError("deadline_before_staging")
    return remaining


def guest_identity():
    os.setgroups([])
    os.setgid(UID)
    os.setuid(UID)
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
        os._exit(125)
    with open("/proc/self/status", encoding="ascii") as stream:
        facts = dict(line.split(":", 1) for line in stream if ":" in line)
    if (
        any(int(facts[key].strip(), 16) != 0 for key in ("CapPrm", "CapEff", "CapAmb"))
        or facts["Seccomp"].strip() != "2"
    ):
        os._exit(125)
    os.umask(0o077)


def guest_processes():
    result = []
    for entry in os.scandir("/proc"):
        if not entry.name.isdigit():
            continue
        try:
            with open(entry.path + "/status", encoding="ascii") as stream:
                facts = dict(line.split(":", 1) for line in stream if ":" in line)
            if int(facts["Uid"].split()[0]) == UID and not facts["State"].lstrip().startswith("Z"):
                result.append(int(entry.name))
        except (FileNotFoundError, ProcessLookupError):
            pass
    return result


class Supervisor:
    def __init__(self, task_deadline_epoch_millis):
        if os.getpid() != 1 or os.getuid() != 0:
            raise RuntimeError("supervisor_requires_container_pid1")
        self.verify_limits()
        os.umask(0o077)
        # Docker's device tmpfs is needed for /dev/null and pipes, but it must not
        # provide an additional guest-writable shared-memory or message store.
        for path in ("/dev/shm", "/dev/mqueue"):
            try:
                os.chmod(path, 0o555)
            except OSError as error:
                if error.errno not in (errno.EROFS, errno.ENOENT):
                    raise
        os.mkdir(CONTROL, 0o700)
        for folder, mode in (("input", 0o700), ("output", 0o777), ("scratch", 0o777)):
            os.mkdir(ROOT + "/" + folder, mode)
            os.chmod(ROOT + "/" + folder, mode)
        self.selector = selectors.DefaultSelector()
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(SOCKET)
        self.server.listen(4)
        self.selector.register(self.server, selectors.EVENT_READ, "control")
        self.proc = None
        self.started = None
        self.deadline = None
        # The fixed entrypoint receives the trusted task deadline, never a guest argument.
        self.task_deadline = time.monotonic() + max(0, task_deadline_epoch_millis / 1000 - time.time())
        self.ttl = self.task_deadline
        self.streams = {"stdout": bytearray(), "stderr": bytearray()}
        self.truncated = {"stdout": False, "stderr": False}
        self.open_pipes = 0
        self.requested = []
        self.result = None
        self.reason = None
        self.stop_started = None
        signal.signal(signal.SIGALRM, self.watchdog)
        self.initial_oom_kills = self.oom_kills()

    @staticmethod
    def verify_limits():
        def read(name):
            with open("/sys/fs/cgroup/" + name, encoding="ascii") as stream:
                return stream.read().strip()

        if read("memory.max") != "536870912" or read("memory.swap.max") != "0" or read("pids.max") != "64":
            raise RuntimeError("kernel_resource_limits_not_enforced")
        quota, period = read("cpu.max").split()
        if quota == "max" or int(quota) > int(period):
            raise RuntimeError("kernel_cpu_limit_not_enforced")
        storage = os.statvfs(ROOT)
        if storage.f_blocks * storage.f_frsize > 134217728:
            raise RuntimeError("kernel_storage_limit_not_enforced")

    @staticmethod
    def oom_kills():
        try:
            with open("/sys/fs/cgroup/memory.events", encoding="ascii") as stream:
                return int(dict(line.split() for line in stream)["oom_kill"])
        except (OSError, KeyError, ValueError):
            raise RuntimeError("cgroup_v2_memory_evidence_required")

    def watchdog(self, _signal, _frame):
        # A root-only kernel timer interrupts blocking control I/O as well as normal polling.
        # The guest cannot signal this UID or cancel its timer. JVM death has no effect.
        if self.result is not None:
            return
        self.reason = self.reason or "timed_out"
        end = time.monotonic() + 2
        while True:
            processes = guest_processes()
            if not processes:
                break
            for pid in processes:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            if time.monotonic() >= end:
                os._exit(124)
            time.sleep(0.002)

    def run(self, request):
        if self.proc is not None or self.result is not None:
            raise ValueError("already_dispatched")
        if set(request) != {
            "command",
            "code",
            "inputs",
            "outputs",
            "execution_millis",
            "deadline_epoch_millis",
        }:
            raise ValueError("invalid_request")
        code = request["code"].encode("utf-8")
        if not code or len(code) > 65536:
            raise ValueError("invalid_limits")
        self.started = time.monotonic()
        remaining = min(execution_seconds(request), self.task_deadline - self.started)
        if remaining <= 0:
            raise ValueError("deadline_before_staging")
        self.deadline = self.started + remaining
        self.ttl = self.deadline + 180
        signal.setitimer(signal.ITIMER_REAL, remaining)
        if len(request["inputs"]) > 10 or len(request["outputs"]) > 10:
            raise ValueError("file_count_limit")
        names = set()
        total = 0
        for item in request["inputs"]:
            if set(item) != {"filename", "data"}:
                raise ValueError("invalid_input")
            name = filename(item["filename"])
            if name.lower() in names:
                raise ValueError("duplicate_input")
            names.add(name.lower())
            data = base64.b64decode(item["data"], validate=True)
            total += len(data)
            if len(data) > MAX_FILE or total > MAX_INPUT:
                raise ValueError("input_size_limit")
            with open(ROOT + "/input/" + name, "xb") as stream:
                stream.write(data)
            os.chmod(ROOT + "/input/" + name, 0o444)
        os.chmod(ROOT + "/input", 0o555)
        self.requested = [filename(name) for name in request["outputs"]]
        if len({name.lower() for name in self.requested}) != len(self.requested):
            raise ValueError("duplicate_output")
        with open(ROOT + "/source.py", "xb") as stream:
            stream.write(code)
        os.chmod(ROOT + "/source.py", 0o444)
        environment = {
            "PATH": "/usr/local/bin:/usr/bin:/bin",
            "HOME": ROOT + "/scratch",
            "TMPDIR": ROOT + "/scratch",
            "MPLCONFIGDIR": ROOT + "/scratch/matplotlib",
            "MPLBACKEND": "Agg",
            "OPENBLAS_NUM_THREADS": "1",
            "OMP_NUM_THREADS": "1",
            "PYTHONDONTWRITEBYTECODE": "1",
            "LANG": "C.UTF-8",
        }
        if self.reason is not None or time.monotonic() >= self.deadline:
            raise ValueError("deadline_before_launch")
        self.proc = subprocess.Popen(
            ["/usr/local/bin/python3", "-I", "-B", ROOT + "/source.py"],
            cwd=ROOT + "/scratch",
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
            close_fds=True,
            start_new_session=True,
            preexec_fn=guest_identity,
        )
        for stream, name in ((self.proc.stdout, "stdout"), (self.proc.stderr, "stderr")):
            os.set_blocking(stream.fileno(), False)
            self.selector.register(stream, selectors.EVENT_READ, name)
            self.open_pipes += 1
        return {"state": "running", "profile": PROFILE}

    def drain(self, stream, name):
        try:
            chunk = os.read(stream.fileno(), 65536)
        except BlockingIOError:
            return
        if not chunk:
            self.selector.unregister(stream)
            stream.close()
            self.open_pipes -= 1
            return
        retained = self.streams[name]
        remaining = MAX_STREAM - len(retained)
        retained.extend(chunk[:remaining])
        if len(chunk) > remaining:
            self.truncated[name] = True

    def advance(self):
        if self.proc is None or self.result is not None:
            return
        now = time.monotonic()
        exit_code = self.proc.poll()
        if now >= self.deadline and self.reason is None and exit_code is None:
            self.reason = "timed_out"
        if exit_code is None and self.reason is None:
            return
        if self.stop_started is None:
            self.stop_started = now
        # A detached session or double fork cannot escape a PID namespace / UID scan.
        for pid in guest_processes():
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        exit_code = self.proc.poll()
        if guest_processes() or exit_code is None or self.open_pipes:
            if now - self.stop_started > 3:
                os._exit(124)  # PID 1 exit kills every process, but evidence is uncertain.
            return
        while True:
            try:
                if os.waitpid(-1, os.WNOHANG)[0] == 0:
                    break
            except ChildProcessError:
                break
        status = self.reason or (
            "succeeded"
            if exit_code == 0
            else "resource_limited"
            if self.oom_kills() > self.initial_oom_kills
            else "failed"
        )
        signal.setitimer(signal.ITIMER_REAL, 0)
        self.result = {
            "state": "terminal",
            "status": status,
            "exit_code": exit_code,
            "stdout": bytes(self.streams["stdout"]).decode("utf-8", errors="replace"),
            "stderr": bytes(self.streams["stderr"]).decode("utf-8", errors="replace"),
            "stdout_truncated": self.truncated["stdout"],
            "stderr_truncated": self.truncated["stderr"],
            "duration_ms": int((now - self.started) * 1000),
            "termination_confirmed": True,
            "failure_code": None if status == "succeeded" else status,
            "outputs": [],
        }

    def collect(self):
        if self.result is None:
            return {"state": "running"}
        result = dict(self.result)
        outputs = []
        total = 0
        deadline = self.ttl
        directory = os.open(ROOT + "/output", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for name in self.requested:
                try:
                    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
                    with os.fdopen(descriptor, "rb") as stream:
                        info = os.fstat(stream.fileno())
                        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                            raise ValueError("unsafe_output")
                        if info.st_size > MAX_FILE or total + info.st_size > MAX_OUTPUT:
                            raise ValueError("output_size_limit")
                        data = stream.read(MAX_FILE + 1)
                    total += len(data)
                    if len(data) > MAX_FILE or total > MAX_OUTPUT or time.monotonic() > deadline:
                        raise ValueError("output_size_limit")
                    outputs.append({"filename": name, "data": base64.b64encode(data).decode("ascii")})
                except FileNotFoundError:
                    result["failure_code"] = "missing_outputs"
                    if result["status"] == "succeeded":
                        result["status"] = "missing_outputs"
                except (OSError, ValueError):
                    result["failure_code"] = "invalid_output"
                    if result["status"] == "succeeded":
                        result["status"] = "failed"
            result["outputs"] = outputs
        finally:
            os.close(directory)
        return result

    def control(self):
        connection, _ = self.server.accept()
        with connection:
            # The task deadline controls active work; post-run collection uses the cleanup TTL.
            boundary = self.deadline if self.proc is not None and self.result is None else self.ttl
            connection.settimeout(max(0.001, boundary - time.monotonic()))
            try:
                body = bytearray()
                while True:
                    chunk = connection.recv(65536)
                    if not chunk:
                        break
                    body.extend(chunk)
                    if len(body) > MAX_REQUEST:
                        raise ValueError("request_size_limit")
                request = json.loads(body)
                command = request.get("command")
                if command == "run":
                    response = self.run(request)
                elif command == "status":
                    response = self.result or {
                        "state": "running" if self.proc else "waiting",
                        "epoch_millis": int(time.time() * 1000),
                    }
                elif command == "collect":
                    response = self.collect()
                elif command == "cancel":
                    if self.proc is None:
                        self.result = {
                            "state": "terminal",
                            "status": "cancelled",
                            "exit_code": None,
                            "stdout": "",
                            "stderr": "",
                            "stdout_truncated": False,
                            "stderr_truncated": False,
                            "duration_ms": 0,
                            "termination_confirmed": True,
                            "failure_code": "cancelled",
                            "outputs": [],
                        }
                    elif self.result is None:
                        self.reason = "cancelled"
                    response = {"state": "cancelling"}
                else:
                    raise ValueError("invalid_command")
                connection.sendall(json.dumps(response, separators=(",", ":")).encode("utf-8"))
            except (ValueError, KeyError, TypeError, OSError):
                try:
                    connection.sendall(b'{"error":"runner_protocol_error"}')
                except OSError:
                    pass

    def serve(self):
        while time.monotonic() < self.ttl:
            self.advance()
            for key, _ in self.selector.select(0.025):
                if key.data == "control":
                    self.control()
                else:
                    self.drain(key.fileobj, key.data)
        # Container PID 1 exiting guarantees remaining namespace processes die.
        os._exit(0 if self.result is not None else 124)


if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        raise SystemExit("trusted_task_deadline_required")
    Supervisor(int(sys.argv[1])).serve()

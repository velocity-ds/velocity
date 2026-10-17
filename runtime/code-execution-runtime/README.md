# Python code execution runtime

Builds the `velocity-python-v3` Linux amd64 Docker image used by the `code_run` tool. Velocity never
builds or pulls it during tool execution — install it explicitly.

| File | Role |
| --- | --- |
| `Dockerfile` | Base pinned by digest; installs hash-verified wheels; removes pip; labels the profile. |
| `supervisor.py` | PID 1 supervisor: stages inputs, runs code as UID 65532, enforces the deadline, kills the process tree, collects requested outputs. |
| `control.py` | Private control protocol between Java and the supervisor. |
| `requirements.lock`, `dependency-manifest.json` | Locked dependency set and selected wheel identities. |
| `PROVENANCE.md` | Source and licensing notes. |

Profile contents: Python 3.12.12, pandas 2.3.3, NumPy 2.3.4, matplotlib 3.10.7, openpyxl 3.1.5,
pypdf 6.10.0, pdfplumber 0.11.9, ReportLab 4.4.9, pypdfium2 5.13.0. No OCR, Poppler, office suite
or formula engine.

## Build

Docker Engine with Linux containers, cgroup v2 CPU/memory/PID limits and default seccomp. From the
repository root:

```sh
docker build --platform linux/amd64 -t velocity-code-python:v3 runtime/code-execution-runtime
docker image inspect --format '{{.Id}}' velocity-code-python:v3
docker image inspect --format '{{index .Config.Labels "net.velocity.code.profile"}}' velocity-code-python:v3
```

The label must read `velocity-python-v3`. Configure the printed immutable ID:

```properties
AGENT_CODE_EXECUTION_IMAGE=sha256:<64 hex digits>
AGENT_CODE_EXECUTION_INSTALLATION_ID=velocity-local-worker
```

Tags are rejected. Let all runs and cleanup settle before changing the image or installation ID.
Runs admitted under older v1/v2 images can still be reconciled; new runs need v3.

## Boundary

Read-only root filesystem, no network, no host mounts or Docker socket, 1 CPU / 512 MiB / 64
processes, 128 MiB tmpfs for `/input`, `/output` and `/tmp`. The guest has no capabilities; the
supervisor keeps only `SETUID`, `SETGID`, `KILL` and `DAC_READ_SEARCH`. Containers share the host
kernel — use a dedicated, patched worker VM for stronger isolation.

See [docs/code-execution.md](../../docs/code-execution.md).

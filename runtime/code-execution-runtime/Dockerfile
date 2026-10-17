FROM --platform=linux/amd64 python:3.12.12-slim-bookworm@sha256:2986c55feb36e6cae00fa1fefb454283e4b33f35e75ff8bdd123b134130be301

LABEL net.velocity.code.profile="velocity-python-v3" \
      org.opencontainers.image.title="Velocity Python execution runtime" \
      org.opencontainers.image.description="Fixed Python data and document libraries and a privileged supervisor for unprivileged generated code"

COPY --chown=0:0 requirements.lock /opt/velocity/requirements.lock
RUN /usr/local/bin/python3 -I -m pip install --disable-pip-version-check --no-cache-dir \
        --only-binary=:all: --require-hashes --no-compile -r /opt/velocity/requirements.lock \
    && /usr/local/bin/python3 -I -m pip check

COPY --chown=0:0 supervisor.py control.py /opt/velocity/
RUN /usr/local/bin/python3 -I -m pip uninstall --yes pip \
    && rm -rf /tmp \
    && ln -s /work/scratch /tmp \
    && ln -s /work/input /input \
    && ln -s /work/output /output \
    && chmod 0555 /opt/velocity \
    && chmod 0444 /opt/velocity/supervisor.py /opt/velocity/control.py /opt/velocity/requirements.lock

# DockerSandboxClient creates only a bounded /work tmpfs; no VOLUME or host bind
# is declared. PID 1 starts as root to supervise UID 65532 and retain its watchdog.
USER 0:0
WORKDIR /
ENTRYPOINT ["/usr/local/bin/python3", "-I", "-B", "/opt/velocity/supervisor.py"]

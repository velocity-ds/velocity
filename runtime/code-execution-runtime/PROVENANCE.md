# Runtime dependency provenance

The `velocity-python-v3` profile targets **Linux amd64, CPython 3.12.12**. Runtime
installation is a separate operator build, and every admitted run records the
resulting immutable image identity. The application rejects mutable image tags.

## Python base

The Dockerfile pins the official Docker Library Python image
`python:3.12.12-slim-bookworm` to its Linux amd64 child manifest:

```text
sha256:2986c55feb36e6cae00fa1fefb454283e4b33f35e75ff8bdd123b134130be301
```

The corresponding multi-platform index inspected during implementation was:

```text
sha256:593bd06efe90efa80dc4eee3948be7c0fde4134606dd40d8dd8dbcade98e669c
```

The tag is descriptive; the child manifest digest selects the actual base.
Primary references are the [official Python image source](https://github.com/docker-library/python)
and [Docker Hub's Python image](https://hub.docker.com/_/python). No operating
system package installation runs against an unpinned apt repository during this
build. Rebuilding from the same pinned inputs reproduces the dependency profile;
Docker metadata and build tooling can still change the final image ID.

## Python libraries

| Distribution | Profile version | Primary release metadata |
| --- | --- | --- |
| pandas | 2.3.3 | [PyPI release JSON](https://pypi.org/pypi/pandas/2.3.3/json) |
| NumPy | 2.3.4 | [PyPI release JSON](https://pypi.org/pypi/numpy/2.3.4/json) |
| matplotlib | 3.10.7 | [PyPI release JSON](https://pypi.org/pypi/matplotlib/3.10.7/json) |
| openpyxl | 3.1.5 | [PyPI release JSON](https://pypi.org/pypi/openpyxl/3.1.5/json) |
| pypdf | 6.10.0 | [PyPI release JSON](https://pypi.org/pypi/pypdf/6.10.0/json) |
| pdfplumber | 0.11.9 | [PyPI release JSON](https://pypi.org/pypi/pdfplumber/0.11.9/json) |
| ReportLab | 4.4.9 | [PyPI release JSON](https://pypi.org/pypi/reportlab/4.4.9/json) |
| pypdfium2 | 5.13.0 | [PyPI release JSON](https://pypi.org/pypi/pypdfium2/5.13.0/json) |

The dependency closure was resolved on 2026-10-03 with pip 26.2.1 for CPython
3.12 Linux amd64. [The dependency manifest](dependency-manifest.json) records
the selected wheel filenames, exact versions, SHA-256 hashes, `Requires-Python`
constraints and declared dependencies from that resolution. The eight public
profile versions were fixed before resolution; transitive versions are now
pinned as well. pandas 2.3.3 requires NumPy 1.26 or newer on Python 3.12, and
matplotlib 3.10.7 requires NumPy 1.23 or newer; selected NumPy 2.3.4 satisfies both.
All selected wheel Python requirements admit Python 3.12.

The resolution and subsequent successful hash-verified dry run used:

```sh
python -m pip --isolated install --dry-run --ignore-installed --require-hashes \
  --only-binary=:all: --platform manylinux_2_28_x86_64 \
  --platform manylinux_2_27_x86_64 --platform manylinux_2_24_x86_64 \
  --platform manylinux2014_x86_64 --python-version 3.12 --implementation cp \
  --abi cp312 --index-url https://pypi.org/simple -r requirements.lock
```

The resolution fixed the eight profile packages and wrote pip's JSON
report; the committed manifest and lock were generated from that report. All
selected wheel downloads passed `--require-hashes` without installing anything
on the Velocity host. This verifies dependency metadata and downloaded bytes; it
does not substitute for the actual Linux image build or Docker isolation validation.

`requirements.lock` contains exact versions and SHA-256 wheel hashes for the
complete resolved dependency closure. The build uses `--only-binary=:all:` and
`--require-hashes`: missing wheels, unresolved dependencies, and changed bytes
fail installation instead of building an unpinned source distribution. `pip check`
validates declared dependency compatibility. Automated image-build smoke cases
are temporarily removed under the [repository verification policy](../../AGENTS.md).
Pip is removed after dependency installation. The PDFium shared library is
supplied by the pinned pypdfium2 wheel; the build does not download a separate renderer.

Version 2 adds the PDF libraries while keeping the existing data library pins.
Neither OCR nor a spreadsheet formula calculation engine is included. The runtime
image preserves installed distributions' license metadata, including PDFium's
bundled dependency notices; no Anthropic skill files or scripts are included.

Actual build and Docker isolation results must be reported separately. Updating
a dependency requires a reviewed lock refresh, another image build, and manual
validation of the deployment boundary. Drain existing work and cleanup before
changing the configured runtime identity. Package metadata and successful imports
do not establish sandbox isolation.

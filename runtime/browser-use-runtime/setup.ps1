param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$runtimeDirectory = $PSScriptRoot
$environmentDirectory = Join-Path $runtimeDirectory '.venv'
& $Python -c 'import sys; assert (3, 11) <= sys.version_info < (4, 0), "Python 3.11 or newer is required"'
if ($LASTEXITCODE -ne 0) { throw 'A supported Python installation is required.' }
& $Python -m venv $environmentDirectory
if ($LASTEXITCODE -ne 0) { throw 'The browser environment could not be created.' }
$runtimePython = Join-Path $environmentDirectory 'Scripts/python.exe'
& $runtimePython -m pip install --disable-pip-version-check -r (Join-Path $runtimeDirectory 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'The pinned browser dependencies could not be installed.' }
Write-Output $runtimePython

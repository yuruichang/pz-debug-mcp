param([string]$Python = 'python', [string]$ZomboidDir = (Join-Path $env:USERPROFILE 'Zomboid'), [switch]$Development)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$runtime = Join-Path $root '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $runtime)) {
    & $Python -m venv (Join-Path $root '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11+ is required' }
}
& $runtime -m pip install -e $root
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
if ($Development) {
    & $runtime -m pip install -r (Join-Path $root 'requirements-dev.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Test dependency installation failed' }
}
& $runtime (Join-Path $root 'tools/generate_config.py') --zomboid-dir $ZomboidDir
if ($LASTEXITCODE -ne 0) { throw 'Configuration generation failed' }
Write-Output 'Setup complete. See README.md and mcp-config.json.'

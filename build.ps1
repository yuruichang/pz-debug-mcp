param([string]$GameDir = 'E:\Steam\steamapps\common\ProjectZomboid', [switch]$InstallMod,
    [string]$ZomboidDir = (Join-Path $env:USERPROFILE 'Zomboid'), [switch]$RefreshCatalog)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$runtime = Join-Path $root '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $runtime)) { throw 'Run setup.ps1 -Development first' }
$env:PYTHONUTF8 = '1'
if ($RefreshCatalog) {
    & $runtime (Join-Path $root 'tools/generate_catalog.py') --game-dir $GameDir
    if ($LASTEXITCODE -ne 0) { throw 'Official API signature extraction failed' }
}
& $runtime -m unittest discover -s (Join-Path $root 'tests') -p 'test_*.py' -v
if ($LASTEXITCODE -ne 0) { throw 'Python/Lua/MCP tests failed' }
$gameJar = Join-Path $GameDir 'projectzomboid.jar'
$gameJava = Join-Path $GameDir 'jre64/bin/java.exe'
if (-not (Test-Path -LiteralPath $gameJar)) { throw 'Game jar required for Kahlua verification; pass -GameDir' }
$classes = Join-Path $root 'build/kahlua'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& javac -encoding UTF-8 -cp $gameJar -d $classes (Join-Path $root 'tests/KahluaCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Kahlua test compilation failed' }
Push-Location -LiteralPath $GameDir
try {
    & $gameJava -cp "$classes;$gameJar;$GameDir" KahluaCheck $root
    if ($LASTEXITCODE -ne 0) { throw 'Shipped Kahlua runtime tests failed' }
} finally { Pop-Location }
& $runtime (Join-Path $root 'tools/generate_config.py') --zomboid-dir $ZomboidDir
if ($LASTEXITCODE -ne 0) { throw 'Configuration generation failed' }
& $runtime (Join-Path $root 'tools/package.py')
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
if ($InstallMod) { & (Join-Path $root 'install-mod.ps1') -ZomboidDir $ZomboidDir }

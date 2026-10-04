param([string]$StagingDir = (Join-Path $env:USERPROFILE 'Zomboid/Workshop/PZDebugMCP'),
    [string]$GameDir = 'E:\Steam\steamapps\common\ProjectZomboid',
    [string]$ZomboidDir = (Join-Path $env:USERPROFILE 'Zomboid'))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $root 'build/workshop'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$gameJar = Join-Path $GameDir 'projectzomboid.jar'
$staging = [IO.Path]::GetFullPath($StagingDir)
& javac -encoding UTF-8 -cp "$GameDir;$gameJar" -d $classes (Join-Path $PSScriptRoot 'WorkshopCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Workshop validator compilation failed' }
Push-Location -LiteralPath $GameDir
try {
    & (Join-Path $GameDir 'jre64/bin/java.exe') -cp "$classes;$GameDir;$gameJar" WorkshopCheck $staging ([IO.Path]::GetFullPath($ZomboidDir))
    if ($LASTEXITCODE -ne 0) { throw 'Shipped Workshop validation failed' }
} finally { Pop-Location }

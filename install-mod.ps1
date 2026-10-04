param([string]$ZomboidDir = (Join-Path $env:USERPROFILE 'Zomboid'))
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'Contents/mods/PZDebugMCP'
foreach ($file in @('workshop.txt', 'preview.png', 'Contents/mods/PZDebugMCP/common/mod.info',
    'Contents/mods/PZDebugMCP/common/poster.png', 'Contents/mods/PZDebugMCP/common/icon.png',
    'Contents/mods/PZDebugMCP/42/media/java/PZDebugMCP.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $file) -PathType Leaf)) {
        throw "Required package file missing: $file. Build the package before installing."
    }
}
if (Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue) { throw 'Close the game before moving or replacing its loaded Java mod.' }
$cache = [IO.Path]::GetFullPath($ZomboidDir).TrimEnd([IO.Path]::DirectorySeparatorChar)
$local = [IO.Path]::GetFullPath((Join-Path $cache 'mods/PZDebugMCP'))
$staging = [IO.Path]::GetFullPath((Join-Path $cache 'Workshop/PZDebugMCP'))
$target = [IO.Path]::GetFullPath((Join-Path $staging 'Contents/mods/PZDebugMCP'))
$backupRoot = [IO.Path]::GetFullPath((Join-Path $cache 'backups/PZDebugMCP'))
function Assert-CachePath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($cache + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Path outside cache: $full" }
    $cursor = $full
    while ($cursor -and $cursor.Length -ge $cache.Length) {
        if ((Test-Path -LiteralPath $cursor) -and
            ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to traverse a linked directory: $cursor"
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
}
foreach ($path in @($local, $staging, $target, $backupRoot)) { Assert-CachePath $path }
function Backup-Mod([string]$Path, [string]$Label) {
    $backup = Join-Path $backupRoot ($Label + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    Assert-CachePath $Path
    Assert-CachePath $backup
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    Move-Item -LiteralPath $Path -Destination $backup
    Write-Output "Previous mod backed up: $backup"
}
New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
if (Test-Path -LiteralPath $target) { Backup-Mod $target 'workshop' }
if (Test-Path -LiteralPath $local) {
    # Move the exact named local mod; no duplicate active Mod ID remains.
    Assert-CachePath $local
    Assert-CachePath $target
    Move-Item -LiteralPath $local -Destination $target
    foreach ($entry in Get-ChildItem -LiteralPath $source -Force) {
        Copy-Item -LiteralPath $entry.FullName -Destination $target -Recurse -Force
    }
    Write-Output "Local mod moved: $local -> $target"
} else {
    Copy-Item -LiteralPath $source -Destination $target -Recurse
}
foreach ($file in @('workshop.txt', 'preview.png')) {
    $destination = Join-Path $staging $file
    # Preserve an existing item's ID, visibility, description and custom preview.
    if (-not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $destination
    }
}
Write-Output "Workshop staging ready: $staging"
Write-Output 'Enable ZombieBuddy and PZDebugMCP and restart with -debug.'

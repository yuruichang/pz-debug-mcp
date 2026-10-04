param([string]$ZomboidDir = (Join-Path $env:USERPROFILE 'Zomboid'))
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'Contents/mods/PZDebugMCP'
if (-not (Test-Path -LiteralPath (Join-Path $source '42/media/java/PZDebugMCP.jar'))) {
    throw 'Java bridge JAR is missing. Run build.ps1 before installing from source.'
}
$modsDir = [IO.Path]::GetFullPath((Join-Path $ZomboidDir 'mods'))
$target = [IO.Path]::GetFullPath((Join-Path $modsDir 'PZDebugMCP'))
if ($target -ne (Join-Path $modsDir 'PZDebugMCP')) { throw 'Unexpected mod target' }
New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
if (Test-Path -LiteralPath $target) {
    if ((Get-Item -LiteralPath $target).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing to replace a linked mod directory' }
    $backupRoot = [IO.Path]::GetFullPath((Join-Path $ZomboidDir 'backups/PZDebugMCP'))
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    $backup = Join-Path $backupRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    if (-not $backup.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected backup target' }
    # Resolved source is the exact named mod; backup is confined to its backup directory.
    Move-Item -LiteralPath $target -Destination $backup
    Write-Output "Previous version backed up: $backup"
}
Copy-Item -LiteralPath $source -Destination $target -Recurse
Write-Output "Mod installed: $target"
Write-Output 'Enable ZombieBuddy and PZDebugMCP, restart with -debug, and approve the Java JAR when prompted.'

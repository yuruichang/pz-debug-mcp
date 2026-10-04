param([string]$GameDir = 'E:\Steam\steamapps\common\ProjectZomboid',
    [int]$Port = 8801, [switch]$Disable)
$ErrorActionPreference = 'Stop'
if ($Port -lt 1 -or $Port -gt 65535) { throw 'JDWP port must be 1..65535' }
if (Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue) { throw 'Exit the game before updating its JVM launch options.' }
$path = [IO.Path]::GetFullPath((Join-Path $GameDir 'ProjectZomboid64.json'))
if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Game launcher JSON not found' }
$original = [IO.File]::ReadAllText($path)
$config = $original | ConvertFrom-Json
if (-not ($config.vmArgs -is [Array]) -or -not $config.mainClass) { throw 'Unexpected game launcher format' }
$managedPattern = '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:*'
$vmArgs = @($config.vmArgs | Where-Object { $_ -notlike $managedPattern })
if (-not $Disable) {
    if ($vmArgs | Where-Object { $_ -like '-agentlib:jdwp=*' }) { throw 'A different JDWP configuration already exists; use its port or update it explicitly.' }
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "Port $Port is already listening; choose another local port." }
    $vmArgs += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$Port"
}
if ((@($vmArgs) -join [char]0) -eq (@($config.vmArgs) -join [char]0)) {
    Write-Output 'Requested JDWP settings already present.'
    return
}
$backup = $path + '.pzdebug-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
Copy-Item -LiteralPath $path -Destination $backup
$config.vmArgs = $vmArgs
$updated = $config | ConvertTo-Json -Depth 12
$parsed = $updated | ConvertFrom-Json
if (-not ($parsed.vmArgs -is [Array])) { throw 'Launch configuration verification failed' }
[IO.File]::WriteAllText($path, $updated + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output "Launcher backup: $backup"
Write-Output $(if ($Disable) {'JDWP disabled.'} else {"JDWP enabled on 127.0.0.1:$Port; suspend=n. Restart the game with -debug."})

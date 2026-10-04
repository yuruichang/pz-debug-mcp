param([switch]$Test)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = Join-Path $root 'build/debugger'
$classes = Join-Path $build 'java'
$bin = Join-Path $root 'bin'
New-Item -ItemType Directory -Path $classes,$bin -Force | Out-Null
$compiler = (Get-Command javac).Source
$jdkBin = Split-Path -Parent $compiler
& $compiler --add-modules jdk.jdi -g -encoding UTF-8 -d $classes (Join-Path $root 'java/src/main/java/com/yuruichang/pzdebug/Json.java') (Join-Path $root 'debugger/java/com/yuruichang/pzdebug/JdiDebugger.java')
if ($LASTEXITCODE -ne 0) { throw 'JDI helper compilation failed' }
& (Join-Path $jdkBin 'jar.exe') --create --file (Join-Path $bin 'java-debugger.jar') --main-class com.yuruichang.pzdebug.JdiDebugger -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JDI helper packaging failed' }
& $compiler -g -d (Join-Path $build 'fixtures') (Join-Path $root 'debugger/java/fixture/DebugSubject.java')
if ($LASTEXITCODE -ne 0) { throw 'Java debug fixture compilation failed' }
$programFiles = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
$vswhere = Join-Path $programFiles 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual Studio C++ build tools are required for the native debugger' }
$vcvars = Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
$driver = Join-Path $build 'compile.cmd'
$source = Join-Path $root 'debugger/native/native_debugger.cpp'
$fixture = Join-Path $root 'debugger/native/fixture.cpp'
$nativeExe = Join-Path $bin 'native-debugger.exe'
$fixtureExe = Join-Path $build 'native-fixture.exe'
$nativeObj = Join-Path $build 'native-debugger.obj'
$fixtureObj = Join-Path $build 'native-fixture.obj'
$commands = @(
    ('call "' + $vcvars + '" >nul'),
    ('cl /nologo /utf-8 /std:c++17 /EHsc /MT /O2 "' + $source + '" /Fo"' + $nativeObj + '" /Fe"' + $nativeExe + '" /link dbgeng.lib ole32.lib'),
    'if errorlevel 1 exit /b 1',
    ('cl /nologo /utf-8 /std:c++17 /EHsc /MT /Od /Zi "' + $fixture + '" /Fo"' + $fixtureObj + '" /Fd"' + (Join-Path $build 'fixture-compile.pdb') + '" /Fe"' + $fixtureExe + '" /link /DEBUG:FULL /PDB:"' + (Join-Path $build 'native-fixture.pdb') + '"'),
    'if errorlevel 1 exit /b 1'
)
[IO.File]::WriteAllLines($driver, [string[]]$commands, [Text.UTF8Encoding]::new($false))
& cmd.exe /d /c $driver
if ($LASTEXITCODE -ne 0) { throw 'Native debugger compilation failed' }
foreach ($library in @('dbghelp.dll', 'dbgcore.dll')) {
    Copy-Item -LiteralPath (Join-Path ([Environment]::SystemDirectory) $library) -Destination $bin -Force
}
$env:PZDEBUG_JDI_JAVA = Join-Path $jdkBin 'java.exe'
$env:PZDEBUG_NATIVE_FIXTURE = $fixtureExe
Write-Output "Debugger helpers built: $bin"

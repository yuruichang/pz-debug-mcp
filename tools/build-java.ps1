param([string]$GameDir = 'E:\Steam\steamapps\common\ProjectZomboid', [switch]$Test)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$compiler = (Get-Command javac -ErrorAction Stop).Source
$jarTool = Join-Path (Split-Path -Parent $compiler) 'jar.exe'
$java = Join-Path $GameDir 'jre64/bin/java.exe'
$gameJar = Join-Path $GameDir 'projectzomboid.jar'
$buddyJar = Join-Path $GameDir 'ZombieBuddy.jar'
if (-not (Test-Path -LiteralPath $buddyJar)) { throw 'Install ZombieBuddy 2.3.2 before building the Java bridge' }
$classes = Join-Path $root 'build/java/classes'
function Reset-BuildDirectory([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $root 'build/java')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected Java build directory' }
    if (Test-Path -LiteralPath $resolved) {
        if ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing to clean a linked build directory' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolved -Force | Out-Null
}
Reset-BuildDirectory $classes
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'java/src/main/java') -Filter '*.java' -Recurse | Sort-Object FullName)
$argsFile = Join-Path $root 'build/java/sources.txt'
$sourceNames = @($sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[IO.File]::WriteAllLines($argsFile, [string[]]$sourceNames, [Text.UTF8Encoding]::new($false))
$classpath = "$GameDir;$gameJar;$buddyJar"
& $compiler --release 25 -g -encoding UTF-8 -cp $classpath -d $classes "@$argsFile"
if ($LASTEXITCODE -ne 0) { throw 'Java bridge compilation failed' }
$destination = Join-Path $root 'Contents/mods/PZDebugMCP/42/media/java'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$manifest = Join-Path $root 'build/java/MANIFEST.MF'
@('Manifest-Version: 1.0', 'Implementation-Title: PZDebugMCP', 'Implementation-Version: 0.4.0', '') | Set-Content -LiteralPath $manifest -Encoding ascii
& $jarTool --create --file (Join-Path $destination 'PZDebugMCP.jar') --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Java bridge packaging failed' }
if ($Test) {
    $testClasses = Join-Path $root 'build/java/tests'
    Reset-BuildDirectory $testClasses
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'java/src/test/java') -Filter '*.java' -Recurse | Sort-Object FullName)
    $testArgs = Join-Path $root 'build/java/test-sources.txt'
    $testNames = @($testSources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    [IO.File]::WriteAllLines($testArgs, [string[]]$testNames, [Text.UTF8Encoding]::new($false))
    & $compiler --release 25 -encoding UTF-8 -cp "$classes;$classpath" -d $testClasses "@$testArgs"
    if ($LASTEXITCODE -ne 0) { throw 'Java test compilation failed' }
    $patchFixture = Join-Path $root 'build/java/patch-fixture.jar'
    & $jarTool --create --file $patchFixture -C $testClasses fixture/PatchFixture.class
    if ($LASTEXITCODE -ne 0) { throw 'Patch fixture packaging failed' }
    & $java -cp "$classes;$testClasses;$classpath" com.yuruichang.pzdebug.PatchCheck $patchFixture
    if ($LASTEXITCODE -ne 0) { throw 'Patch catalog checks failed' }
    $agentManifest = Join-Path $root 'build/java/TEST-AGENT.MF'
    @('Manifest-Version: 1.0', 'Premain-Class: fixture.TestAgent', 'Can-Retransform-Classes: true', '') | Set-Content -LiteralPath $agentManifest -Encoding ascii
    $agentJar = Join-Path $root 'build/java/test-agent.jar'
    & $jarTool --create --file $agentJar --manifest $agentManifest -C $testClasses fixture/TestAgent.class
    if ($LASTEXITCODE -ne 0) { throw 'Test agent packaging failed' }
    $env:PZDEBUG_JAVA_TEST_CP = "$classes;$testClasses;$classpath"
    $env:PZDEBUG_JAVA_TEST_EXE = $java
    $env:PZDEBUG_JAVA_TEST_AGENT = $agentJar
    & $java --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED "-javaagent:$agentJar" -cp $env:PZDEBUG_JAVA_TEST_CP com.yuruichang.pzdebug.TestMain
    if ($LASTEXITCODE -ne 0) { throw 'Java bridge checks failed' }
    Push-Location -LiteralPath $GameDir
    try {
        & $java --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED "-javaagent:$agentJar" -cp $env:PZDEBUG_JAVA_TEST_CP com.yuruichang.pzdebug.DebugCheck
        if ($LASTEXITCODE -ne 0) { throw 'Kahlua debugger checks failed' }
    } finally { Pop-Location }
}

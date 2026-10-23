param([switch]$Install, [switch]$Offline, [string]$CachePath, [string]$DeviceSerial)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) { throw 'Set ANDROID_HOME to Android SDK (platform android-35, build-tools 35.0.0).' }
$gradlePath = Join-Path $PSScriptRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlePath)) {
    $gradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
    if ($gradleCommand) { $gradlePath = $gradleCommand.Source }
    else {
        $candidate = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-8.9-bin') -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $candidate) { throw 'Install Gradle 8.9 or use Android Studio to build this project.' }
        $gradlePath = $candidate.FullName
    }
}
$gradleArgs = @('--no-daemon', '--project-dir', $PSScriptRoot)
if ($Offline) { $gradleArgs += '--offline' }
if ($CachePath) { $gradleArgs += @('--gradle-user-home', $CachePath) }
& $gradlePath @gradleArgs assembleDebug testDebugUnitTest
if ($LASTEXITCODE -ne 0) { throw 'Android build failed. See Gradle error above.' }
$outputPath = Join-Path $projectPath 'dist/android'
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$apkPath = Join-Path $outputPath 'AquaStream-debug.apk'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $apkPath -Force
if ($Install) {
    $deviceArgs = @()
    if ($DeviceSerial) { $deviceArgs = @('-s', $DeviceSerial) }
    & adb @deviceArgs install -r $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'ADB install failed' }
    & adb @deviceArgs shell am start -n vn.aquastream.tv/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'ADB launch failed' }
}
Write-Host "Built: $apkPath"

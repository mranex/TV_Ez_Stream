param([string]$AdbPath,[string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$outputPath = Join-Path $projectPath 'dist/windows'
if($OutputDirectory){$outputPath=$OutputDirectory}
& python -m PyInstaller --noconfirm --clean --onefile --windowed --name AquaStreamBridge --paths $PSScriptRoot --hidden-import progress_store --hidden-import bridge_runtime --hidden-import bridge_config --hidden-import connection_manager --hidden-import config_ui --hidden-import lan_config_ui --hidden-import lan_transport --hidden-import lan_firewall --collect-all zeroconf --icon (Join-Path $PSScriptRoot 'AquaStream.ico') --distpath $outputPath --workpath (Join-Path $projectPath 'windows/build') --specpath (Join-Path $projectPath 'windows/build') (Join-Path $PSScriptRoot 'bridge.py')
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller failed. Install: python -m pip install -r windows/requirements-build.txt' }
if (-not $AdbPath) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $AdbPath = $adbCommand.Source }
}
if ($AdbPath) {
    $platformPath = Join-Path $outputPath 'platform-tools'
    New-Item -ItemType Directory -Force $platformPath | Out-Null
    foreach ($name in @('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt')) {
        $sourcePath = Join-Path (Split-Path $AdbPath -Parent) $name
        if (Test-Path -LiteralPath $sourcePath) { Copy-Item -LiteralPath $sourcePath -Destination $platformPath -Force }
    }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-Bridge.ps1') -Destination $outputPath -Force
Write-Host "Built: $outputPath"

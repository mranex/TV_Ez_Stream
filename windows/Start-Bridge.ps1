param([switch]$Stop)
$ErrorActionPreference = 'Stop'
$exePath = Join-Path $PSScriptRoot 'AquaStreamBridge.exe'
if (-not (Test-Path -LiteralPath $exePath)) { throw 'Build first: powershell -File windows/build.ps1' }
if ($Stop) {
    $controller = Start-Process -FilePath $exePath -ArgumentList @('--control', 'shutdown') -WindowStyle Hidden -Wait -PassThru
    if ($controller.ExitCode -ne 0) { throw 'Backend unavailable. Open AquaStreamBridge.exe to inspect its status.' }
    Write-Host 'Backend shutdown requested.'
    return
}
Start-Process -FilePath $exePath -ArgumentList '--background' -WorkingDirectory $PSScriptRoot -WindowStyle Hidden | Out-Null
Write-Host 'Background startup requested (an existing backend is reused).'

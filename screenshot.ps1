param(
    [string]$OutputFile = ""
)

# 1. Resolve ADB
$adb = "adb"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    if (Test-Path "C:\platform-tools\adb.exe") {
        $adb = "C:\platform-tools\adb.exe"
    } else {
        Write-Host "[X] adb was not found in PATH or C:\platform-tools" -ForegroundColor Red
        exit 1
    }
}

# 2. Determine file name
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $OutputFile = "screenshot_$timestamp.png"
} elseif (-not $OutputFile.EndsWith(".png", [System.StringComparison]::OrdinalIgnoreCase)) {
    $OutputFile += ".png"
}

# 3. Capture directly to PC in one single command
cmd.exe /c "`"$adb`" exec-out screencap -p > `"$OutputFile`""

if ((Test-Path $OutputFile) -and (Get-Item $OutputFile).Length -gt 0) {
    Write-Host "[✓] Screenshot saved to $OutputFile ($([math]::Round((Get-Item $OutputFile).Length / 1KB, 1)) KB)" -ForegroundColor Green
} else {
    Write-Host "[X] Failed to capture screenshot. Check phone connection and adb devices." -ForegroundColor Red
}

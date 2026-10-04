@echo off
setlocal

:: Resolve ADB
where adb >nul 2>nul
if %errorlevel% equ 0 (
    set ADB=adb
) else if exist "C:\platform-tools\adb.exe" (
    set ADB=C:\platform-tools\adb.exe
) else (
    echo [X] adb was not found in PATH or C:\platform-tools
    exit /b 1
)

:: Filename
set "OUTFILE=%~1"
if "%OUTFILE%"=="" (
    set OUTFILE=screenshot.png
)

:: Capture
"%ADB%" exec-out screencap -p > "%OUTFILE%"
if exist "%OUTFILE%" (
    echo [✓] Screenshot saved to %OUTFILE%
) else (
    echo [X] Failed to capture screenshot.
)

endlocal

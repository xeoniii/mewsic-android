@echo off
setlocal

:: Set JDK 21 for Android build compatibility
if exist "C:\Work\jdk-21" (
    set "JAVA_HOME=C:\Work\jdk-21"
    set "PATH=C:\Work\jdk-21\bin;%PATH%"
)

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

:: Check device
echo ==^> Checking for connected Android device...
%ADB% devices | findstr /R /C:"[a-zA-Z0-9].*device$" >nul
if %errorlevel% neq 0 (
    echo [!] No authorized Android device detected.
    echo     Please connect your phone, enable USB Debugging, and accept the prompt.
    exit /b 1
)

:: Build APK
echo ==^> Compiling Mewsic (Debug)...
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo [X] Build failed!
    exit /b %errorlevel%
)

:: Install APK
echo ==^> Installing APK onto device...
%ADB% install -r app\build\outputs\apk\debug\app-debug.apk
if %errorlevel% neq 0 (
    echo [X] Installation failed!
    exit /b %errorlevel%
)

:: Launch App
echo ==^> Launching Mewsic instantly...
%ADB% shell am start -n com.mewsic.app/.MainActivity

echo.
echo [SUCCESS] Mewsic is running on your phone!
endlocal

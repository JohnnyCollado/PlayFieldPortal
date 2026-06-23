@echo off
setlocal EnableExtensions EnableDelayedExpansion

pushd "%~dp0"

set "ADB=adb"
set "ADB_FOUND=0"
where adb >nul 2>nul
if not errorlevel 1 (
    set "ADB_FOUND=1"
) else (
    if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
    if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_FOUND=1"
)
if "%ADB_FOUND%"=="0" (
    echo adb was not found on PATH.
    echo Install Android platform-tools or set ANDROID_HOME.
    popd
    exit /b 1
)

set "APK="
for /f "delims=" %%F in ('dir /b /s /a-d /o-d "%~dp0app\build\outputs\apk\*.apk" 2^>nul') do (
    if not defined APK set "APK=%%F"
)

if not defined APK (
    echo No built APK was found under app\build\outputs\apk.
    echo Run build-debug-apk.bat first.
    popd
    exit /b 1
)

echo APK to install:
echo %APK%
echo.

echo Connected Android devices:
"%ADB%" devices
echo.

set /a COUNT=0
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
    if "%%B"=="device" (
        set /a COUNT+=1
        set "DEVICE_!COUNT!=%%A"
        echo !COUNT!. %%A
    )
)

if %COUNT% EQU 0 (
    echo No installable devices found.
    echo Connect a device, enable USB debugging, and approve the RSA prompt.
    popd
    exit /b 1
)

echo.
set /p CHOICE=Choose a device number to install to: 

if not defined DEVICE_%CHOICE% (
    echo Invalid device selection.
    popd
    exit /b 1
)

set "TARGET=!DEVICE_%CHOICE%!"
echo.
echo Installing to !TARGET!...
"%ADB%" -s "!TARGET!" install -r "%APK%"
if errorlevel 1 (
    echo.
    echo Install failed.
    popd
    exit /b 1
)

echo.
echo Install complete.
popd
exit /b 0

@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM ============================================================
REM  Play Field Portal - APK Installer
REM  Pick an APK from dist\ or debug\, pick a connected device,
REM  and install it with `adb install -r` (keeps app data).
REM
REM  Usage: install-apk.bat ["path\to\some.apk"]
REM ============================================================

pushd "%~dp0"

echo.
echo ========================================
echo Play Field Portal - APK Installer
echo ========================================

REM -- Locate adb ----------------------------------------------
set "_ADB="
for /f "delims=" %%a in ('where adb 2^>nul') do if not defined _ADB set "_ADB=%%a"
if not defined _ADB if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "_ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined _ADB if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "_ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined _ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

if not defined _ADB (
    echo ERROR: adb was not found. 1>&2
    echo Add platform-tools to PATH or set ANDROID_HOME. 1>&2
    popd
    exit /b 1
)

REM -- Choose the APK ------------------------------------------
set "_APK="
if not "%~1"=="" (
    if not exist "%~f1" (
        echo ERROR: APK not found: %~1 1>&2
        popd
        exit /b 1
    )
    set "_APK=%~f1"
)

if not defined _APK (
    set "_N=0"
    echo.
    echo Available APKs:
    for %%f in ("%~dp0dist\*.apk") do (
        set /a _N+=1
        set "_APKLIST[!_N!]=%%~ff"
        echo   !_N!. %%~nxf   [dist]
    )
    for %%f in ("%~dp0debug\*.apk") do (
        set /a _N+=1
        set "_APKLIST[!_N!]=%%~ff"
        echo   !_N!. %%~nxf   [debug]
    )

    if "!_N!"=="0" (
        echo ERROR: no APKs found in dist\ or debug\. 1>&2
        echo Build one first with build-release-apk.bat. 1>&2
        popd
        exit /b 1
    )

    echo.
    set /p "_APKCHOICE=APK [1-!_N!]: "
    if not defined _APKCHOICE (
        echo ERROR: no APK selected. 1>&2
        popd
        exit /b 1
    )
    for /f "delims=0123456789" %%x in ("!_APKCHOICE!") do (
        echo ERROR: invalid APK selection "!_APKCHOICE!". 1>&2
        popd
        exit /b 1
    )
    if !_APKCHOICE! lss 1 (
        echo ERROR: invalid APK selection "!_APKCHOICE!". 1>&2
        popd
        exit /b 1
    )
    if !_APKCHOICE! gtr !_N! (
        echo ERROR: invalid APK selection "!_APKCHOICE!". 1>&2
        popd
        exit /b 1
    )
    for %%i in (!_APKCHOICE!) do set "_APK=!_APKLIST[%%i]!"
)

if not defined _APK (
    echo ERROR: could not resolve the selected APK. 1>&2
    popd
    exit /b 1
)
if not exist "%_APK%" (
    echo ERROR: APK not found: %_APK% 1>&2
    popd
    exit /b 1
)

REM -- Choose the device ---------------------------------------
set "_DEVFILE=%TEMP%\pfp-devices-%RANDOM%.txt"
"%_ADB%" devices -l > "%_DEVFILE%" 2>nul

set "_D=0"
for /f "usebackq skip=1 tokens=1,2*" %%a in ("%_DEVFILE%") do (
    if /i "%%b"=="device" (
        set /a _D+=1
        set "_SERIALS[!_D!]=%%a"
        set "_MODEL=unknown"
        set "_INFO=%%c"
        for %%m in (!_INFO!) do (
            set "_T=%%m"
            if /i "!_T:~0,6!"=="model:" set "_MODEL=!_T:~6!"
        )
        set "_MODELS[!_D!]=!_MODEL!"
    )
)
del "%_DEVFILE%" >nul 2>nul

if "%_D%"=="0" (
    echo ERROR: no device is ready. 1>&2
    echo Devices reporting "unauthorized" or "offline" are ignored -- check the 1>&2
    echo USB-debugging prompt on the device, then run: "%_ADB%" devices 1>&2
    popd
    exit /b 1
)

if "%_D%"=="1" (
    set "_SERIAL=!_SERIALS[1]!"
    set "_DEVMODEL=!_MODELS[1]!"
) else (
    echo.
    echo Connected devices:
    for /l %%i in (1,1,%_D%) do echo   %%i. !_SERIALS[%%i]!   ^(!_MODELS[%%i]!^)
    echo.
    set /p "_DEVCHOICE=Device [1-%_D%]: "
    if not defined _DEVCHOICE (
        echo ERROR: no device selected. 1>&2
        popd
        exit /b 1
    )
    for /f "delims=0123456789" %%x in ("!_DEVCHOICE!") do (
        echo ERROR: invalid device selection "!_DEVCHOICE!". 1>&2
        popd
        exit /b 1
    )
    if !_DEVCHOICE! lss 1 (
        echo ERROR: invalid device selection "!_DEVCHOICE!". 1>&2
        popd
        exit /b 1
    )
    if !_DEVCHOICE! gtr %_D% (
        echo ERROR: invalid device selection "!_DEVCHOICE!". 1>&2
        popd
        exit /b 1
    )
    for %%i in (!_DEVCHOICE!) do (
        set "_SERIAL=!_SERIALS[%%i]!"
        set "_DEVMODEL=!_MODELS[%%i]!"
    )
)

REM -- Install --------------------------------------------------
echo.
for %%A in ("%_APK%") do echo APK    : %%~nxA   ^(%%~zA bytes^)
echo Device : %_SERIAL%   ^(%_DEVMODEL%^)
echo.
echo Installing ^(adb install -r^)...
echo.

"%_ADB%" -s %_SERIAL% install -r "%_APK%"

if errorlevel 1 (
    echo.
    echo ========================================
    echo INSTALL FAILED
    echo ========================================
    echo.
    echo If adb reported a signature mismatch or a downgrade, uninstall the 1>&2
    echo existing app first ^(this erases its data^), then re-run: 1>&2
    echo   "%_ADB%" -s %_SERIAL% uninstall ^<applicationId^> 1>&2
    echo.
    popd
    exit /b 1
)

echo.
echo ========================================
echo INSTALL SUCCESS
echo ========================================
echo.
popd
exit /b 0

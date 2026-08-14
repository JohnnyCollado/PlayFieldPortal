@echo off
setlocal EnableExtensions EnableDelayedExpansion

pushd "%~dp0"

REM ============================================================
REM  Play Field Portal - APK Installer
REM  Choose flavor (full/lite) and build type (debug/release),
REM  then install the matching APK to a connected device.
REM ============================================================

REM ── Locate adb ──────────────────────────────────────────────
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

REM ── Choose flavor ───────────────────────────────────────────
echo.
echo Choose flavor:
echo   1. full   ^(includes the Discord Social SDK^)
echo   2. lite   ^(smaller download, no Discord native libs^)
echo.
set /p FLAVORCHOICE=Flavor [1-2]:
if "%FLAVORCHOICE%"=="1" (
    set "FLAVORLC=full"
    set "FLAVORUC=Full"
) else if "%FLAVORCHOICE%"=="2" (
    set "FLAVORLC=lite"
    set "FLAVORUC=Lite"
) else (
    echo Invalid flavor selection.
    popd
    exit /b 1
)

REM ── Choose build type ───────────────────────────────────────
echo.
echo Choose build type:
echo   1. debug
echo   2. release
echo.
set /p TYPECHOICE=Build type [1-2]:
if "%TYPECHOICE%"=="1" (
    set "TYPELC=debug"
    set "TYPEUC=Debug"
) else if "%TYPECHOICE%"=="2" (
    set "TYPELC=release"
    set "TYPEUC=Release"
) else (
    echo Invalid build type selection.
    popd
    exit /b 1
)

set "VARIANT=%FLAVORLC%-%TYPELC%"
set "TASK=:app:assemble%FLAVORUC%%TYPEUC%"

REM ── Where to look for the built APK ─────────────────────────
REM Debug builds are mirrored into <root>\debug by the copy task wired into
REM assemble*Debug (see app/build.gradle.kts), named
REM PlayFieldPortal-<version>-<flavor>-debug.apk. Glob it so the version in the
REM filename never needs to be hardcoded here. Release builds keep the standard
REM Gradle output path.
if "%TYPELC%"=="debug" (
    set "APKPATTERN=%~dp0debug\PlayFieldPortal-*-%FLAVORLC%-debug.apk"
) else (
    set "APKPATTERN=%~dp0app\build\outputs\apk\%FLAVORLC%\%TYPELC%\app-%FLAVORLC%-%TYPELC%.apk"
)

call :resolveApk

echo.
echo Selected variant: %VARIANT%
echo APK path:         !APK!
echo.

REM ── Build if the chosen APK isn't there yet ─────────────────
if not defined APK (
    echo APK not found for %VARIANT%.
    set /p BUILDNOW=Build it now with gradlew %TASK%? [Y/N]:
    if /i "!BUILDNOW!"=="Y" (
        echo.
        echo Building %VARIANT%...
        call "%~dp0gradlew.bat" --console=plain -Dorg.gradle.problems.report=false %TASK%
        if errorlevel 1 (
            echo.
            echo Build failed.
            popd
            exit /b 1
        )
        REM assemble*Debug finalizes into <root>\debug, so re-resolve the glob.
        call :resolveApk
    ) else (
        echo.
        echo Nothing to install. Build it first with:
        echo   gradlew.bat %TASK%
        popd
        exit /b 1
    )
)

if not defined APK (
    echo Build finished but no APK matched: !APKPATTERN!
    echo ^(A release APK also needs a signing key - see keystore.properties.^)
    popd
    exit /b 1
)

REM ── Choose device ───────────────────────────────────────────
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
echo Installing %VARIANT% to !TARGET!...
"%ADB%" -s "!TARGET!" install -r "%APK%"
if errorlevel 1 (
    echo.
    echo Install failed.
    popd
    exit /b 1
)

echo.
echo Install complete ^(%VARIANT%^).
popd
exit /b 0

REM ── Resolve APKPATTERN to a concrete APK path ───────────────
REM Leaves APK undefined when nothing matches. For the debug glob, the for-loop
REM enumerates only files that actually exist; for the concrete release path it
REM would echo the literal even if absent, so existence is re-checked after.
REM (If several versioned debug APKs linger in <root>\debug, the last match wins;
REM clear the folder to be sure of the one installed.)
:resolveApk
set "APK="
for %%F in (%APKPATTERN%) do set "APK=%%~fF"
if defined APK if not exist "!APK!" set "APK="
exit /b 0

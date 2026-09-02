@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM ============================================================
REM  Play Field Portal - Release APK Builder
REM  Builds a signed release APK for the chosen flavor. Gradle's
REM  copy{Flavor}ReleaseToDist task (app/build.gradle.kts) is what
REM  renames and drops the APK into <root>\dist as
REM  PlayFieldPortal-<version>-<flavor>.apk -- this script drives
REM  and verifies that, it never copies or renames on its own.
REM
REM  Usage: build-release-apk.bat [full^|lite^|both]
REM ============================================================

pushd "%~dp0"

set "LOG=%~dp0build-release.log"

echo.
echo ========================================
echo Play Field Portal - Release APK Builder
echo ========================================

REM -- Flavor selection (argument wins over the prompt) ---------
set "_CHOICE=%~1"
if not defined _CHOICE (
    echo.
    echo Choose flavor:
    echo   1. full   ^(includes the Discord Social SDK^)
    echo   2. lite   ^(smaller download, no Discord native libs^)
    echo   3. both
    echo.
    set /p "_CHOICE=Flavor [1-3]: "
)

set "_FLAVORS="
if /i "%_CHOICE%"=="1"    set "_FLAVORS=full"
if /i "%_CHOICE%"=="full" set "_FLAVORS=full"
if /i "%_CHOICE%"=="2"    set "_FLAVORS=lite"
if /i "%_CHOICE%"=="lite" set "_FLAVORS=lite"
if /i "%_CHOICE%"=="3"    set "_FLAVORS=full lite"
if /i "%_CHOICE%"=="both" set "_FLAVORS=full lite"

if not defined _FLAVORS (
    echo ERROR: invalid flavor selection "%_CHOICE%". Expected 1, 2, 3, full, lite or both. 1>&2
    popd
    exit /b 1
)

REM -- Signing preflight ---------------------------------------
if not exist "%~dp0keystore.properties" (
    echo.
    echo WARNING: keystore.properties not found.
    echo The release build will be UNSIGNED and cannot be installed on a device.
)

REM -- Read versionName from app/build.gradle.kts --------------
REM  Only used to predict the output filename for verification and
REM  the summary. Gradle owns the actual naming.
set "_VERSION="
for /f "tokens=2 delims==" %%v in ('findstr /r /c:"versionName *=" "%~dp0app\build.gradle.kts"') do (
    set "_RAW=%%v"
    set "_RAW=!_RAW: =!"
    set _RAW=!_RAW:"=!
    set "_VERSION=!_RAW!"
)

if not defined _VERSION (
    echo ERROR: could not read versionName from app\build.gradle.kts. 1>&2
    popd
    exit /b 1
)

REM -- Build the Gradle task list ------------------------------
set "_TASKS="
for %%F in (%_FLAVORS%) do (
    if "%%F"=="full" set "_TASKS=!_TASKS! :app:assembleFullRelease"
    if "%%F"=="lite" set "_TASKS=!_TASKS! :app:assembleLiteRelease"
)

echo.
echo Version : %_VERSION%
echo Flavor  : %_FLAVORS%
echo Tasks   :%_TASKS%
echo Log     : %LOG%
echo.
echo Building... this can take a few minutes.

echo Build started %DATE% %TIME% > "%LOG%"
echo Command: gradlew.bat --console=plain -Dorg.gradle.problems.report=false%_TASKS% >> "%LOG%"
echo. >> "%LOG%"

call "%~dp0gradlew.bat" --console=plain -Dorg.gradle.problems.report=false%_TASKS% >> "%LOG%" 2>&1

if errorlevel 1 (
    echo.
    echo ========================================
    echo BUILD FAILED
    echo ========================================
    echo.
    echo Review the full log: %LOG% 1>&2
    echo.
    popd
    exit /b 1
)

REM -- Verify the versioned APKs landed in dist ----------------
set "_FAIL=0"
echo.
echo ========================================
echo BUILD SUCCESS
echo ========================================
echo.
echo Artifacts in %~dp0dist:
for %%F in (%_FLAVORS%) do (
    set "_APK=%~dp0dist\PlayFieldPortal-%_VERSION%-%%F.apk"
    if exist "!_APK!" (
        for %%A in ("!_APK!") do echo   %%~nxA   ^(%%~zA bytes^)
    ) else (
        echo   MISSING: PlayFieldPortal-%_VERSION%-%%F.apk 1>&2
        set "_FAIL=1"
    )
)

if "%_FAIL%"=="1" (
    echo.
    echo ERROR: Gradle succeeded but an expected APK is not in dist. 1>&2
    echo Check the copy task in app\build.gradle.kts and the log: %LOG% 1>&2
    popd
    exit /b 1
)

echo.
echo Install one with: install-apk.bat
echo.
popd
exit /b 0

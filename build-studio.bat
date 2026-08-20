@echo off
REM ============================================================================
REM  build-studio.bat - Build PlayField Theme Studio, run its tests, launch it.
REM
REM  Runs the :studio unit tests and, if they pass, assembles a runnable app
REM  image, then launches it so it can be tested.
REM
REM  Usage:
REM      build-studio.bat                 Test + build + launch
REM      build-studio.bat NOLAUNCH        Test + build only (no launch)
REM  Any args after an optional leading NOLAUNCH are forwarded to Gradle, e.g.
REM      build-studio.bat --info
REM      build-studio.bat NOLAUNCH --rerun-tasks
REM ============================================================================

setlocal

REM Always run from the repo root (this script's own directory), so it works
REM no matter where it is invoked from.
cd /d "%~dp0"

REM Optional leading NOLAUNCH flag: build but do not launch the app.
set "LAUNCH=1"
if /I "%~1"=="NOLAUNCH" (
    set "LAUNCH=0"
    shift
)

REM Collect any remaining arguments to forward to Gradle (NOLAUNCH excluded).
set "GRADLE_ARGS="
:collect_args
if "%~1"=="" goto args_done
set "GRADLE_ARGS=%GRADLE_ARGS% %1"
shift
goto collect_args
:args_done

echo.
echo === Testing Theme Studio ===
echo.

REM Run tests separately so packaging cannot start if they fail.
call ".\gradlew.bat" :studio:test%GRADLE_ARGS%
set "TEST_EXIT=%ERRORLEVEL%"

if not "%TEST_EXIT%"=="0" (
    echo.
    echo === TESTS FAILED ^(exit %TEST_EXIT%^) ===
    endlocal & exit /b %TEST_EXIT%
)

echo.
echo === Packaging Theme Studio ^(createDistributable^) ===
echo.

call ".\gradlew.bat" :studio:createDistributable%GRADLE_ARGS%
set "BUILD_EXIT=%ERRORLEVEL%"

if not "%BUILD_EXIT%"=="0" (
    echo.
    echo === BUILD FAILED ^(exit %BUILD_EXIT%^) ===
    endlocal & exit /b %BUILD_EXIT%
)

echo.
echo === BUILD SUCCESSFUL ===
echo App image: studio\build\compose\binaries\main\app\PlayField Theme Studio
echo Test report: studio\build\reports\tests\test\index.html

REM Launch the freshly built app so it can be tested. `start` returns immediately,
REM leaving Theme Studio running in its own window.
set "APP_EXE=studio\build\compose\binaries\main\app\PlayField Theme Studio\PlayField Theme Studio.exe"
if "%LAUNCH%"=="0" (
    echo Skipping launch ^(NOLAUNCH^).
) else if exist "%APP_EXE%" (
    echo Launching Theme Studio...
    start "" "%APP_EXE%"
) else (
    echo Could not find built executable to launch: "%APP_EXE%"
)
endlocal & exit /b 0

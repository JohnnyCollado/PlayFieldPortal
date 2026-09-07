@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM ============================================================
REM  Play Field Portal - Theme Studio (dev run)
REM  Compiles :studio and launches it straight from the build
REM  output via Compose Desktop's `run` task. This is the test
REM  loop -- it does NOT package anything. For an installer use
REM  build-theme-studio-installer.bat.
REM
REM  Gradle output streams to the console on purpose: this script
REM  is interactive, and the Studio's own stdout/stderr (theme
REM  parse warnings, stack traces) is the point of running it.
REM
REM  Usage: run-theme-studio.bat [--clean] [--tests]
REM     --clean   Wipe :studio build output before compiling
REM     --tests   Run :studio unit tests first; abort if they fail
REM ============================================================

pushd "%~dp0"

REM Exit code used by :usage -- 0 when help was asked for, 1 on a bad option.
set "_RC=1"

set "_CLEAN=0"
set "_TESTS=0"

REM  One SHIFT at the bottom, guarded by an if/else chain. Never write
REM  `if cond set X=1 & shift & goto` -- cmd splits that line on & at the
REM  top level, so the shift and goto run whether or not the if matched
REM  and every flag after the first gets eaten.
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--clean" (
    set "_CLEAN=1"
) else if /i "%~1"=="--tests" (
    set "_TESTS=1"
) else if /i "%~1"=="--help" (
    set "_RC=0"
    goto :usage
) else if /i "%~1"=="-h" (
    set "_RC=0"
    goto :usage
) else (
    echo ERROR: unknown option "%~1". 1>&2
    echo. 1>&2
    goto :usage
)
shift
goto :parse_args
:args_done

if not exist "%~dp0gradlew.bat" (
    echo ERROR: gradlew.bat not found next to this script. 1>&2
    echo Run this from the repository root. 1>&2
    popd
    exit /b 1
)

echo.
echo ========================================
echo Theme Studio - dev run
echo ========================================
echo.

REM -- Optional clean -------------------------------------------
if "%_CLEAN%"=="1" (
    echo Cleaning :studio build output...
    call "%~dp0gradlew.bat" --console=plain -Dorg.gradle.problems.report=false :studio:clean
    if errorlevel 1 (
        echo. 1>&2
        echo ERROR: clean failed. 1>&2
        popd
        exit /b 1
    )
    echo.
)

REM -- Optional tests -------------------------------------------
REM  Deliberately a gate, not a warning: launching a Studio whose
REM  round-trip tests are red wastes the run.
if "%_TESTS%"=="1" (
    echo Running :studio unit tests...
    call "%~dp0gradlew.bat" --console=plain -Dorg.gradle.problems.report=false :studio:test
    if errorlevel 1 (
        echo. 1>&2
        echo ========================================  1>&2
        echo TESTS FAILED - not launching             1>&2
        echo ========================================  1>&2
        echo. 1>&2
        echo Report: studio\build\reports\tests\test\index.html 1>&2
        popd
        exit /b 1
    )
    echo.
    echo Tests passed.
    echo.
)

echo Compiling and launching Theme Studio...
echo Close the Studio window to return to this prompt.
echo.

call "%~dp0gradlew.bat" --console=plain -Dorg.gradle.problems.report=false :studio:run

if errorlevel 1 (
    echo.
    echo ======================================== 1>&2
    echo RUN FAILED                               1>&2
    echo ======================================== 1>&2
    echo. 1>&2
    echo If the compile succeeded and the window never appeared, check the 1>&2
    echo stack trace above -- Compose Desktop reports startup failures there. 1>&2
    popd
    exit /b 1
)

echo.
echo Theme Studio exited cleanly.
echo.
popd
exit /b 0

:usage
echo Usage: %~nx0 [--clean] [--tests]
echo.
echo Compiles and launches the Theme Studio desktop app for testing.
echo.
echo Options:
echo   --clean   Wipe :studio build output before compiling
echo   --tests   Run :studio unit tests first; abort if they fail
echo   --help    Show this message
echo.
echo Packaging an installer instead: build-theme-studio-installer.bat
popd
exit /b %_RC%

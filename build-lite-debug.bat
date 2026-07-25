@echo off
setlocal

pushd "%~dp0"

set "LOG=%~dp0build-lite-debug.log"
set "APK=%~dp0app\build\outputs\apk\lite\debug\app-lite-debug.apk"

echo.
echo ========================================
echo Play Field Portal - LITE Debug APK Builder
echo ========================================
echo.

echo Build started %DATE% %TIME% > "%LOG%"
echo Command: gradlew.bat --console=plain -Dorg.gradle.problems.report=false :app:assembleLiteDebug >> "%LOG%"
echo. >> "%LOG%"
echo.

REM Run gradle and write output to log
call "%~dp0gradlew.bat" --console=plain -Dorg.gradle.problems.report=false :app:assembleLiteDebug >> "%LOG%" 2>&1

if errorlevel 1 (
    echo.
    echo ========================================
    echo BUILD FAILED
    echo ========================================
    echo.
    echo Review full log: %LOG%
    echo.
    popd
    exit /b 1
)

echo.
if exist "%APK%" (
    echo ========================================
    echo BUILD SUCCESS
    echo ========================================
    echo.
    echo APK: %APK%
    echo.
) else (
    echo ========================================
    echo BUILD INCOMPLETE
    echo ========================================
    echo.
    echo APK not found: %APK%
    echo.
    popd
    exit /b 1
)

popd
exit /b 0

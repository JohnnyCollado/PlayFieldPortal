@echo off
setlocal

pushd "%~dp0"

set "LOG=%~dp0build.log"

echo.
echo ========================================
echo Play Field Portal - Debug APK Builder
echo ========================================
echo.

echo Build started %DATE% %TIME% > "%LOG%"
echo Command: gradlew.bat --console=plain -Dorg.gradle.problems.report=false :app:assembleDebug >> "%LOG%"
echo. >> "%LOG%"
echo.

REM Run gradle and write output to log
call "%~dp0gradlew.bat" --console=plain -Dorg.gradle.problems.report=false :app:assembleDebug >> "%LOG%" 2>&1

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

set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
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

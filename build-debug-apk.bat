@echo off
setlocal

pushd "%~dp0"

set "LOG=%~dp0build.log"

echo Building Play Field Portal debug APK...
echo Writing build output to:
echo %LOG%
echo.

echo Build started %DATE% %TIME% > "%LOG%"
echo Command: gradlew.bat -Dorg.gradle.problems.report=false :app:assembleDebug >> "%LOG%"
echo. >> "%LOG%"

call "%~dp0gradlew.bat" -Dorg.gradle.problems.report=false :app:assembleDebug >> "%LOG%" 2>&1
if errorlevel 1 (
    echo.
    echo Build failed.
    echo Review log:
    echo %LOG%
    popd
    exit /b 1
)

set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
echo.
if exist "%APK%" (
    echo Build complete:
    echo %APK%
    echo. >> "%LOG%"
    echo Build completed %DATE% %TIME% >> "%LOG%"
    echo APK: %APK% >> "%LOG%"
) else (
    echo Build completed, but the expected APK was not found:
    echo %APK%
    echo. >> "%LOG%"
    echo Build completed but expected APK was not found: %APK% >> "%LOG%"
    popd
    exit /b 1
)

popd
exit /b 0

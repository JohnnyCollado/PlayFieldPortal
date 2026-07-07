@echo off
rem Launches the PlayField Theme Studio desktop app (:studio Compose Desktop module).
rem First run downloads dependencies and can take a few minutes; later runs are quick.
setlocal
cd /d "%~dp0"
call gradlew.bat :studio:run --console=plain
if errorlevel 1 (
    echo.
    echo Theme Studio failed to start. Scroll up for the Gradle error.
    pause
)
endlocal

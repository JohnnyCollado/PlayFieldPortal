@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM ============================================================
REM  Play Field Portal - Theme Studio Installer Builder
REM  Packages the Theme Studio as a Windows .exe installer via
REM  Compose Desktop / jpackage. Gradle's copyReleaseInstallerToDist
REM  task (studio/build.gradle.kts) is what flattens the installer
REM  into <root>\dist as PlayField-Theme-Studio-<version>.exe --
REM  this script drives and verifies that, it never copies or
REM  renames on its own.
REM
REM  The installer creates a Desktop shortcut and a Start Menu
REM  entry under "PlayField Theme Studio", and lets the user pick
REM  the install directory. Those come from the windows {} block in
REM  studio/build.gradle.kts (shortcut / menu / menuGroup /
REM  dirChooser) -- change them there, not here.
REM
REM  Requires: WiX Toolset v3 on PATH (jpackage shells out to
REM  candle.exe and light.exe to build any Windows installer).
REM
REM  Usage: build-theme-studio-installer.bat [--msi] [--clean]
REM     --msi     Also build the .msi (managed/silent deployment)
REM     --clean   Wipe :studio build output first
REM ============================================================

pushd "%~dp0"

REM Exit code used by :usage -- 0 when help was asked for, 1 on a bad option.
set "_RC=1"

set "LOG=%~dp0build-theme-studio.log"
set "_ALSO_MSI=0"
set "_CLEAN=0"

REM  One SHIFT at the bottom, guarded by an if/else chain. Never write
REM  `if cond set X=1 & shift & goto` -- cmd splits that line on & at the
REM  top level, so the shift and goto run whether or not the if matched
REM  and every flag after the first gets eaten.
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="--msi" (
    set "_ALSO_MSI=1"
) else if /i "%~1"=="--clean" (
    set "_CLEAN=1"
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

echo.
echo ========================================
echo Theme Studio - Installer Builder
echo ========================================

REM -- WiX preflight --------------------------------------------
REM  jpackage cannot build an .exe or .msi without WiX v3. Failing
REM  here with an actionable message beats a Gradle stack trace
REM  fifteen minutes into a release.
set "_WIX_OK=0"
where candle.exe >NUL 2>&1 && where light.exe >NUL 2>&1 && set "_WIX_OK=1"

REM  Not on PATH: probe the standard install location and prepend it for this
REM  process only. Kept in a subroutine because %ProgramFiles(x86)% carries
REM  literal parentheses that fight the parser inside a ( ) block.
if "%_WIX_OK%"=="0" call :find_wix

if "%_WIX_OK%"=="0" (
    echo. 1>&2
    echo ERROR: WiX Toolset v3 not found. 1>&2
    echo. 1>&2
    echo jpackage needs candle.exe and light.exe to build a Windows installer. 1>&2
    echo Install WiX v3 ^(not v4/v5 -- JDK 17's jpackage only speaks v3^): 1>&2
    echo. 1>&2
    echo   winget install --id WiXToolset.WiXToolset      ^(needs an admin terminal^) 1>&2
    echo. 1>&2
    echo Or, with no admin rights, extract wix314-binaries.zip from 1>&2
    echo https://github.com/wixtoolset/wix3/releases into: 1>&2
    echo. 1>&2
    echo   %~dp0tools\wix3 1>&2
    echo. 1>&2
    echo This script prefers that folder and needs no PATH changes for it. 1>&2
    popd
    exit /b 1
)

REM -- jpackage preflight ---------------------------------------
if not defined JAVA_HOME (
    echo. 1>&2
    echo ERROR: JAVA_HOME is not set. jpackage ships inside the JDK. 1>&2
    popd
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
    echo. 1>&2
    echo ERROR: jpackage.exe not found under %JAVA_HOME%. 1>&2
    echo A JDK 17 or newer is required ^(a JRE will not do^). 1>&2
    popd
    exit /b 1
)

REM -- Read packageVersion from studio/build.gradle.kts ---------
REM  Only used to predict the output filename for verification and
REM  the summary. Gradle owns the actual naming. The leading space
REM  in the pattern keeps msiPackageVersion/exePackageVersion out.
set "_VERSION="
for /f "tokens=2 delims==" %%v in ('findstr /r /c:" packageVersion *=" "%~dp0studio\build.gradle.kts"') do (
    set "_RAW=%%v"
    set "_RAW=!_RAW: =!"
    set _RAW=!_RAW:"=!
    set "_VERSION=!_RAW!"
)

if not defined _VERSION (
    echo ERROR: could not read packageVersion from studio\build.gradle.kts. 1>&2
    popd
    exit /b 1
)

REM -- Build the Gradle task list -------------------------------
set "_TASKS= :studio:packageReleaseExe"
if "%_ALSO_MSI%"=="1" set "_TASKS=!_TASKS! :studio:packageReleaseMsi"
if "%_CLEAN%"=="1"    set "_TASKS= :studio:clean!_TASKS!"

echo.
echo Version : %_VERSION%
echo Formats : exe
if "%_ALSO_MSI%"=="1" echo           msi
echo Tasks   :%_TASKS%
echo Log     : %LOG%
echo.
echo Packaging... jpackage bundles a JVM, so this takes several minutes.

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

REM -- Verify the versioned installers landed in dist -----------
REM  dist names come from the copy task's space-to-hyphen rename.
set "_FAIL=0"
echo.
echo ========================================
echo BUILD SUCCESS
echo ========================================
echo.
echo Artifacts in %~dp0dist:

set "_EXE=%~dp0dist\PlayField-Theme-Studio-%_VERSION%.exe"
if exist "%_EXE%" (
    for %%A in ("%_EXE%") do echo   %%~nxA   ^(%%~zA bytes^)
) else (
    echo   MISSING: PlayField-Theme-Studio-%_VERSION%.exe 1>&2
    set "_FAIL=1"
)

if "%_ALSO_MSI%"=="1" (
    set "_MSI=%~dp0dist\PlayField-Theme-Studio-%_VERSION%.msi"
    if exist "!_MSI!" (
        for %%A in ("!_MSI!") do echo   %%~nxA   ^(%%~zA bytes^)
    ) else (
        echo   MISSING: PlayField-Theme-Studio-%_VERSION%.msi 1>&2
        set "_FAIL=1"
    )
)

if "%_FAIL%"=="1" (
    echo.
    echo ERROR: Gradle succeeded but an expected installer is not in dist. 1>&2
    echo Check copyReleaseInstallerToDist in studio\build.gradle.kts and the log: %LOG% 1>&2
    popd
    exit /b 1
)

echo.
echo The installer adds a Desktop shortcut and a Start Menu entry
echo under "PlayField Theme Studio", and prompts for the install directory.
echo.
popd
exit /b 0

:usage
echo Usage: %~nx0 [--msi] [--clean]
echo.
echo Packages the Theme Studio as a Windows .exe installer into dist\.
echo.
echo Options:
echo   --msi     Also build the .msi ^(managed/silent deployment^)
echo   --clean   Wipe :studio build output first
echo   --help    Show this message
echo.
echo Testing the Studio without packaging: run-theme-studio.bat
popd
exit /b %_RC%

REM -- Locate a WiX v3 install and prepend its bin to PATH ------
REM  Sets _WIX_OK=1 on success. Runs via CALL, not in a subshell, so both
REM  PATH and _WIX_OK persist into the caller. jpackage finds candle.exe and
REM  light.exe by PATH lookup, so prepending is all that is required.
:find_wix
REM  1) Repo-local portable copy. Preferred: it needs no admin install, and it
REM     pins the WiX version the installer is built with instead of inheriting
REM     whatever happens to be on the machine. Populate it by extracting
REM     wix314-binaries.zip (wixtoolset/wix3 releases) into tools\wix3.
if exist "%~dp0tools\wix3\candle.exe" (
    set "PATH=%~dp0tools\wix3;%PATH%"
    set "_WIX_OK=1"
    exit /b 0
)
REM  2) Fall back to a machine-wide WiX v3 install.
set "_PF86=%ProgramFiles(x86)%"
for /d %%D in ("%_PF86%\WiX Toolset v3.*") do (
    if exist "%%~D\bin\candle.exe" (
        set "PATH=%%~D\bin;!PATH!"
        set "_WIX_OK=1"
    )
)
exit /b 0

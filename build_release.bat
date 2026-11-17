@echo off
setlocal
cd /d "%~dp0"

set "APK_DIR=%~dp0app\build\outputs\apk\release"
set "GRADLE_JVM=-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8"

echo.
echo Building app ...
call "%~dp0gradlew.bat" "%GRADLE_JVM%" :app:assembleRelease
if errorlevel 1 goto :fail

rem Both editions build to the same file name, so keep each in its own folder.

echo.
echo ============================================================
echo  APK folder: %APK_DIR%
echo ============================================================
dir /b "%APK_DIR%\*.apk"
if not defined NO_PAUSE (
    start "" explorer "%APK_DIR%"
    pause
)
endlocal
exit /b 0

:fail
echo.
echo ============================================================
echo  BUILD FAILED - see the messages above.
echo ============================================================
if not defined NO_PAUSE pause
endlocal
exit /b 1

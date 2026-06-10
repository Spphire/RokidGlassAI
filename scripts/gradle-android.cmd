@echo off
setlocal

set "REPO_ROOT=%~dp0.."
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

if not defined ANDROID_HOME (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)

set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

pushd "%REPO_ROOT%" >nul
call gradlew.bat --dependency-verification=off %*
set "EXIT_CODE=%ERRORLEVEL%"
popd >nul

exit /b %EXIT_CODE%

@echo off
setlocal
set GRADLE_VERSION=9.6.0
set DIST=%~dp0.gradle-local\gradle-%GRADLE_VERSION%
if not exist "%DIST%\bin\gradle.bat" (
  echo Gradle %GRADLE_VERSION% nie znaleziony. Pobieranie...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip='%~dp0.gradle-local\gradle.zip'; New-Item -ItemType Directory -Force -Path '%~dp0.gradle-local' | Out-Null; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -Force $zip '%~dp0.gradle-local'; Remove-Item $zip"
  if errorlevel 1 exit /b 1
)
call "%DIST%\bin\gradle.bat" %*

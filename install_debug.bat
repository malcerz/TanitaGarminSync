@echo off
setlocal
call build_debug.bat
if errorlevel 1 exit /b 1
where adb >nul 2>nul
if errorlevel 1 (
  echo ADB nie jest w PATH. APK jest w app\build\outputs\apk\debug\app-debug.apk
  exit /b 2
)
adb install -r app\build\outputs\apk\debug\app-debug.apk

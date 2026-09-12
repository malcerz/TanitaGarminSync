@echo off
setlocal
rem Android Gradle Plugin wymaga JVM 17+. Preferuj JBR z Android Studio, jeśli jest.
if exist "C:\Program Files\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android Studio\jbr"
echo JAVA_HOME=%JAVA_HOME%
call gradlew.bat :app:assembleDebug
if errorlevel 1 exit /b 1
echo.
echo APK: app\build\outputs\apk\debug\TanitaGarminSync-debug.apk

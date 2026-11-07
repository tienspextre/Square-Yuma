@echo off
setlocal
set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%desktopApp\build\compose\jars\Square-Yuma-windows-x64-1.0.0.jar

if not exist "%JAR_PATH%" (
    echo Building Square-Yuma Desktop for Windows...
    call "%SCRIPT_DIR%gradlew.bat" -p "%SCRIPT_DIR%" :desktopApp:packageUberJarForCurrentOS
)

java -jar "%JAR_PATH%" %*

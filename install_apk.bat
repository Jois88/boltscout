@echo off
"C:\Users\yosef\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "C:\Users\yosef\bolt-scout\app\build\outputs\apk\debug\app-debug.apk" > "%TEMP%\adb_install_result.txt" 2>&1
echo EXIT_CODE=%ERRORLEVEL% >> "%TEMP%\adb_install_result.txt"
type "%TEMP%\adb_install_result.txt"

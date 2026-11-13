@echo off
setlocal
cd /d "%~dp0\.."

echo [1/3] Running MAJUNG SSE Relay E2E test...
where node >nul 2>nul && node relay-server\verify_e2e_sse.mjs

if exist gradlew.bat if exist gradle\wrapper\gradle-wrapper.jar (
  call gradlew.bat :app:testSimDebugUnitTest :app:assembleSimRelease :app:assemblePleosRelease
) else (
  where gradle >nul 2>nul
  if %ERRORLEVEL% EQU 0 (
    call gradle wrapper --gradle-version 8.10.2
    call gradlew.bat :app:testSimDebugUnitTest :app:assembleSimRelease :app:assemblePleosRelease
  ) else (
    echo Please open this folder in Android Studio ^(JDK 17, Android SDK API 34^) to sync Gradle and build APKs.
    exit /b 1
  )
)

if not exist apk mkdir apk
copy /Y app\build\outputs\apk\sim\release\*.apk apk\majung-v2-sim-release.apk
copy /Y app\build\outputs\apk\pleos\release\*.apk apk\majung-v2-pleos-release.apk
echo Done. APKs copied to apk\ folder.

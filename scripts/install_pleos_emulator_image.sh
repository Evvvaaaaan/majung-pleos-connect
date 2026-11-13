#!/usr/bin/env bash
# Pleos Connect v2.1.0 에뮬레이터 시스템 이미지 설치 (macOS arm64)
# - 공식 가이드의 SDK Update Site(crp-arm64-sys-img.xml)를 ~/.android/repositories.cfg 에 등록하고
# - sdkmanager 로 system-images;android-34;pleos_car_emulator;arm64-v8a (약 4.67 GB) 를 설치한다.
# - 이 스크립트를 실행하면 42dot "Software Development Kit License Agreement" 에 동의하는 것입니다.
#   (전문: https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml 의 <license>)
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
PKG="system-images;android-34;pleos_car_emulator;arm64-v8a"
CFG="$HOME/.android/repositories.cfg"

if ! grep -q "crp-arm64-sys-img.xml" "$CFG" 2>/dev/null; then
  mkdir -p "$HOME/.android"
  printf '### User Sources for Android SDK Manager\ncount=1\ndisp00=Pleos Connect System Image\nsrc00=https\\://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml\n' > "$CFG"
  echo "[1/2] update site 등록: $CFG"
else
  echo "[1/2] update site 이미 등록됨"
fi

echo "[2/2] 설치 시작: $PKG (4.67 GB, 라이선스 자동 동의)"
yes | "$SDKMANAGER" --install "$PKG"
echo "설치 완료:"; ls -la "$SDK/system-images/android-34/pleos_car_emulator/arm64-v8a" | head

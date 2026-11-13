#!/usr/bin/env bash
set -euo pipefail

# 마중(MAJUNG) v2.1 — 로컬 PC(JDK 17 / Android SDK API 34) 원클릭 검증 및 APK 빌드 스크립트
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[1/4] Node.js 실시간 SSE 중계 서버 End-to-End 자가 검증..."
if command -v node >/dev/null 2>&1; then
  node relay-server/verify_e2e_sse.mjs
else
  echo "  (참고: node 미설치 환경이므로 SSE 서버 테스트를 건너뜁니다.)"
fi

echo "[2/4] Gradle 실행기 확인..."
if [[ -x "./gradlew" && -f "gradle/wrapper/gradle-wrapper.jar" ]]; then
  GRADLE_CMD="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
  gradle wrapper --gradle-version 8.10.2
  GRADLE_CMD="./gradlew"
else
  echo "안내: gradle-wrapper.jar 또는 시스템 gradle CLI가 없습니다. Android Studio에서 이 폴더를 열면 자동 생성됩니다."
  exit 1
fi

echo "[3/4] 도메인·신호 흐름 유닛 테스트(12건) 및 시뮬레이션 APK(sim) 빌드..."
"$GRADLE_CMD" --no-daemon :app:testSimDebugUnitTest :app:assembleSimRelease

mkdir -p apk
cp -f app/build/outputs/apk/sim/release/*.apk apk/majung-v2-sim-release.apk
echo "  -> 생성 완료: apk/majung-v2-sim-release.apk"

echo "[4/4] Pleos Connect 실연동 APK(pleos, API 34) 빌드 (nexus-playground.pleos.ai 접근 필요)..."
if "$GRADLE_CMD" --no-daemon :app:assemblePleosRelease; then
  cp -f app/build/outputs/apk/pleos/release/*.apk apk/majung-v2-pleos-release.apk
  echo "  -> 생성 완료: apk/majung-v2-pleos-release.apk"
else
  echo "  (참고: Pleos Maven 접근이 제한된 네트워크입니다. sim 빌드로 먼저 검증하세요.)"
fi

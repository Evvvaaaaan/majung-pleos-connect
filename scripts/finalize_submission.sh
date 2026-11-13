#!/usr/bin/env bash
# 제출 직전 마무리: 공식 규칙(apk/팀명_앱이름_release.apk)대로 APK를 복사·커밋·푸시하고, 이메일 본문에 넣을 URL·커밋 해시를 출력한다.
#   사용: ./scripts/finalize_submission.sh "<팀명 또는 성명>"
set -euo pipefail
TEAM="${1:-}"
if [[ -z "$TEAM" ]]; then echo "사용법: $0 \"<팀명 또는 성명>\""; exit 1; fi
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT_DIR"

SAFE_TEAM="${TEAM// /_}"
TARGET="apk/${SAFE_TEAM}_마중_release.apk"
cp -f apk/majung-v2-pleos-release.apk "$TARGET"
echo "[1/3] APK 복사: $TARGET (Pleos Connect 실연동 빌드)"

git add "$TARGET"
if ! git diff --cached --quiet; then
  git -c user.name=majung -c user.email=majung@users.noreply.github.com commit -q -m "Submission: ${SAFE_TEAM}_마중_release.apk (official naming rule)"
fi
git push -q origin main
HASH="$(git rev-parse HEAD)"
URL="$(git remote get-url origin | sed -E 's#\.git$##')"
echo "[2/3] 푸시 완료 · 최종 커밋 해시: $HASH"

cat <<EOF

[3/3] 이메일에 붙여 넣을 내용 ---------------------------------------------
받는 사람: appdevchallenge@hyundai-partners.com
제목: [차량용 앱 개발 공모전] ${TEAM}_<대표자명>

앱명: 마중 (MAJUNG)
GitHub 저장소: ${URL}
최종 커밋 해시: ${HASH}
APK 경로: ${TARGET} (sim 시연용: apk/majung-v2-sim-release.apk)
시연 영상: docs/demo/majung_demo.mp4 (첨부 동일)
첨부: 마중_접수양식_v3.docx (01·03·04 기입·서명본), majung_demo.mp4
------------------------------------------------------------------------
※ 저장소는 2026.11.16~11.23 Public 유지. 팀 참가 시 팀원 전원 CC.
EOF

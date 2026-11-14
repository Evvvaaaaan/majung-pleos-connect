#!/bin/bash
# 마중 v3 시연 흐름 (Pleos Connect 에뮬레이터, pleos 디버그 빌드) — 녹화와 동시에 실행한다.
#
#   사전 조건: 에뮬레이터 부팅, 앱 설치·실행(대기 화면), relay-server(8080) 실행,
#             Virtual Console 포워딩(adb forward tcp:8088 tcp:8088) + 하트비트 루프, 전원 상태 On.
#   사용:      tools/demo-video/pleos_flow.sh <work-dir>
#   출력:      <work-dir>/taps.json (녹화 시작 기준 각 단계의 ms), <work-dir>/recording.webm, <work-dir>/timeline.jsonl
#
# 위치: Pleos 에뮬레이터는 FusedLocation 주입 수단이 없어 디버그 빌드의 adb 훅(io.majung.pleos.DEBUG_LOCATION)으로
#       좌표를 넣는다. 그 외 모든 신호(기어·차속·방향지시등·도어·착좌·하향등)는 Virtual Console → 실제 Vehicle SDK 콜백이다.
set -u
WORK="${1:?work dir}"; mkdir -p "$WORK"
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
VC="http://127.0.0.1:8088"; RELAY="http://127.0.0.1:8080"
PICK_LAT=37.498812; PICK_LNG=127.061420     # 파리바게뜨 대치점 (safe_pitstops_kr.json)
PAX_LAT=37.498930;  PAX_LNG=127.061640      # 동승자: 간판 앞 인도, 북동쪽 약 25 m

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }
tap() { timeout 10 adb shell input tap "$1" "$2" >/dev/null; }
vc() { curl -s -m 3 -X PUT "$VC/vehicle/$1" >/dev/null; }
loc() { timeout 10 adb shell am broadcast --user 10 -a io.majung.pleos.DEBUG_LOCATION -p io.majung.pleos --ef lat "$1" --ef lng "$2" --ef heading "${3:-90}" >/dev/null 2>&1; }
front() { timeout 10 adb shell am start -n io.majung.pleos/.ui.MainActivity >/dev/null 2>&1; }
sid() { curl -s "$RELAY/api/v1/sessions/_latest" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sessionId"])'; }
# 경도 1도 ≈ 88,300 m (위도 37.5°). 승차 지점에서 서쪽으로 m 만큼 떨어진 경도
west() { python3 -c "print($PICK_LNG - $1/88300.0)"; }

MARKS="$WORK/marks.txt"; : > "$MARKS"   # macOS bash 3.2: 연관 배열 대신 파일에 기록
mark() { local ms=$(( $(now_ms) - T0 )); echo "$1 $ms" >> "$MARKS"; echo "  [$1] $ms ms"; }

vc "lights?feature=headlamp_mode&value=Off"; vc "gear?gear=Park"; vc "controls?speed=0&brake=0&steering=0"
vc "body?door_fr=false&door_rl=false"; vc "seat?kind=occupied&area=passenger&value=true"   # (에뮬레이터 반전: true = 비어 있음)
# 마중 화면을 앞으로 (앱은 이미 실행 중이어야 한다. 대기 판과 종료 판은 같은 높이라 시작 버튼 좌표가 같다)
# 좌표는 2560×1440 전체 화면 기준이며, 앱은 런처 오른쪽 앱 영역(x 853~2560, y 90~1300)에 임베드된다.
for i in 1 2 3 4 5 6; do
  front; sleep 3
  if timeout 10 adb shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus=.*io.majung.pleos"; then echo "app in front"; break; fi
  echo "app not in front yet (try $i)"
done
sleep 2

echo "recording start"
timeout 10 adb emu screenrecord start --size 1600x900 --bit-rate 6000000 "$WORK/recording.webm" >/dev/null
T0=$(now_ms); echo "$T0" > "$WORK/t0.txt"
node "$(dirname "$0")/sse_log.mjs" --base "$RELAY" --out "$WORK/timeline.jsonl" --t0 "$T0" > "$WORK/sse_log.err" 2>&1 &
SSE_PID=$!
sleep 2.5

# 세션 시작 전에 차량 위치를 승차 지점 서쪽 320 m 로 둔다 (이전 시연의 좌표가 남아 있으면 시작 직후 정차로 오판한다).
# 기어 D·차속은 시작 버튼을 누른 뒤에 넣는다 — 주행 중(UX 제한)에는 시작 판의 입력란이 숨어 버튼 위치가 달라진다.
loc 37.4988 "$(west 320)" 90; sleep 1.5
mark start;      tap 1282 508                              # 픽업 시작, 링크 보내기 → 내비 경로 요청(내비 앱 전면)
sleep 5;         front; sleep 2; mark back
vc "gear?gear=Drive"; vc "controls?speed=32"; mark car320
sleep 3;         curl -s -m 3 -X POST "$RELAY/api/v1/sessions/$(sid)/passenger-location" -H 'Content-Type: application/json' -d "{\"lat\":$PAX_LAT,\"lng\":$PAX_LNG,\"accuracyM\":12}" >/dev/null; mark pax
sleep 4;         loc 37.4988 "$(west 240)" 90
sleep 3;         mark cantstop; tap 997 1216                # 여기 못 서요
sleep 3.5;       mark option;   tap 1624 1148               # 앞으로 30 m → 재경로(내비 앱 전면)
sleep 4;         front; sleep 2; mark back2
loc 37.4988 "$(west 170)" 90
sleep 3;         loc 37.4988 "$(west 110)" 90; vc "controls?speed=22"
sleep 3;         curl -s -m 3 -X POST "$RELAY/api/v1/sessions/$(sid)/suggest" -H 'Content-Type: application/json' -d '{"kind":"WAIT_MINUTES","text":"2분만 기다려 주세요","minutes":2}' >/dev/null; mark suggest
sleep 4;         mark accept;   tap 2053 1078               # 수락
sleep 3;         loc 37.498812 "$(python3 -c "print($PICK_LNG + 30/88300.0 - 60/88300.0)")" 90; vc "turn-signal?mode=BlinkRight"; vc "controls?speed=12"; mark near   # 새 승차 지점(+30 m) 60 m 앞, 우측 방향지시등 → 접근 감지
sleep 4;         loc 37.498812 "$(python3 -c "print($PICK_LNG + 30/88300.0)")" 90; vc "controls?speed=0&brake=80"; vc "gear?gear=Park"; vc "turn-signal?mode=Off"; mark stop   # 앞으로 30 m 지점에 P단 정차
# 에뮬레이터의 차속은 서서히 줄어든다 — 기어 P + 차속 0 (stoppedInPark)이 중계 상태에 보일 때까지 기다린다
for i in $(seq 1 60); do
  st=$(curl -s -m 2 "$RELAY/api/v1/sessions/$(sid)" | python3 -c 'import sys,json;s=json.load(sys.stdin);print("1" if s.get("stoppedInPark") and s.get("phase")=="STEP4_CURBSIDE_STOPPED" else "0")' 2>/dev/null)
  [ "$st" = "1" ] && break; sleep 0.5
done
mark stopped
sleep 3;         vc "lights?feature=headlamp_mode&value=LowBeam"; mark night
sleep 4;         mark unlock;   tap 1044 1216               # 주변 확인 끝, 문 열기 → DOOR_LOCK·WINDOW_POS
sleep 5;         vc "body?door_fr=true"; mark doorfr        # 조수석 문 열림 → 독서등(야간)
sleep 4;         vc "seat?kind=occupied&area=passenger&value=false"; mark seated   # 착좌(반전) → 환대 인사
sleep 5;         vc "body?door_fr=false"; mark doorclose
sleep 3;         mark complete; tap 1021 1216               # 탑승 확인, 종료 → 창문·조명 원복, 세션 파기
sleep 6;         mark end
timeout 10 adb emu screenrecord stop >/dev/null; sleep 2
kill $SSE_PID 2>/dev/null
vc "lights?feature=headlamp_mode&value=Off"; vc "seat?kind=occupied&area=passenger&value=true"

python3 - "$WORK/taps.json" "$MARKS" <<'EOF'
import sys, json
out, marks = sys.argv[1], sys.argv[2]
d = {}
for line in open(marks):
    k, v = line.split(); d[k] = int(v)
json.dump(d, open(out, "w"), ensure_ascii=False, indent=1); print(json.dumps(d))
EOF
ls -la "$WORK"

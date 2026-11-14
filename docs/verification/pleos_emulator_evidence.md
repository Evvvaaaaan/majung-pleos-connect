# Pleos Connect v2.1.0 에뮬레이터 실행 검증 기록

- 환경: Pleos Connect Emulator v2.1.0 (`system-images;android-34;pleos_car_emulator;arm64-v8a`, 2560×1440 @160dpi, RAM 4GB, Cold boot, `-gpu host`), macOS Apple Silicon
- 빌드: `apk/majung-v2-pleos-release.apk` (pleos 플레이버, Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TextToSpeech 3.3.1)
- 신호 주입: Pleos Connect Virtual Console (`adb forward tcp:8088 tcp:8088`, `PUT /vehicle/{gear,controls,body,seat,lights,turn-signal,hvac,reading-lights}`)
- 증적 수집: `adb logcat -s MajungSDK MajungHub MajungFlow` (앱이 남기는 SDK 등록·콜백·자동 판단 로그)

## 1. 초기화·등록·능력 조회 (실제 SDK 응답)

```
MajungSDK: init: Vehicle(2.3.2).initialize() 성공 · NaviHelper(3.0.5).initialize() 완료 · FusedLocation(1.1.3) 지원 확인 및 초기화
MajungSDK: Vehicle SDK register* 등록: 16건 성공
MajungHub: AAOS CarPropertyManager PERF_VEHICLE_SPEED 구독 (SENSOR_RATE_UI)
MajungSDK: capabilities: belt=5 occupancy=5 doorPos=6 doorLock=2(HOOD;ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT) window=4(ROW_1_RIGHT 0..10) fan=READ_WRITE(ROW_1_LEFT) temp=READ_WRITE recirc=READ_WRITE seatHeat=READ_WRITE
MajungSDK: Pleos TTS initialize onSuccess (mode=ON_DEVICE)
```

## 2. Virtual Console 주입 → Vehicle SDK 콜백 수신

| 주입 (Virtual Console) | 앱이 받은 콜백 (logcat) |
| :-- | :-- |
| `PUT /vehicle/gear?gear=Drive` / `Park` / `Neutral` | `MajungSDK: gear raw=GEAR_DRIVE → D`, `gear=P`, `gear=N` |
| `PUT /vehicle/controls?speed=40` | `MajungHub: speed=40.0km/h [AAOS]` (CarPropertyManager PERF_VEHICLE_SPEED) |
| `PUT /vehicle/controls?brake=30&steering=90` | `brake=ON`, `steering=-90°` |
| `PUT /vehicle/turn-signal?mode=BlinkRight` | `turnSignal=RIGHT` |
| `PUT /vehicle/lights?feature=headlamp_mode&value=LowBeam` | `lowBeam=ON` |
| `PUT /vehicle/body?front_wiper=Low` | `wiper=ON` |
| `PUT /vehicle/body?door_fr=true` | `door[ROW_1_RIGHT]=OPEN` |
| `PUT /vehicle/body?door_rl=true` | `door[ROW_2_LEFT]=OPEN` → `MajungFlow: 차도측(ROW_2_LEFT) 도어 열림 → 즉시 경고 발화` |
| `PUT /vehicle/seat?kind=occupied&area=passenger&value=false` | `seat[ROW_1_RIGHT]=OCCUPIED` (※ 에뮬레이터는 Virtual Console 값과 반대로 전달됨 — 아래 4절) |
| NaviHelper 경로 안내 중 | `MajungHub: tbt=80m` (`NaviHelperEventListener.onTBTInfo`) |

## 3. 앱 → 차량 제어 (실제 SDK 쓰기, onComplete 확인)

```
MajungFlow: 운전자 수동 지정: 간판 앞 (+12m)
MajungFlow: door.setDoorLock(ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT, value=false) -> onComplete(locked=false)   → Virtual Console door_locks.all = false
MajungFlow: window.setWindowPosition(ROW_1_RIGHT, raw=2 of 0..10) -> onComplete(20%)                                  → Virtual Console windows.front_right = 20
MajungFlow: 주변 확인 완료 · 도어 잠금 해제 · 조수석 창문 25% 개방
MajungFlow: 차도측(ROW_2_LEFT) 도어 열림 → 즉시 경고 발화
MajungFlow: light.setReadingLightSwitch(ROW_1_RIGHT, ON) -> onComplete(ON)                                            → Virtual Console reading_lights.front_right = true
MajungFlow: 착좌 감지 → 환대 인사: 지수님, 반갑습니다.
MajungFlow: 수동 탑승 확인 → 창문·조명·공조 원복 · 세션 즉시 파기                                                     → WINDOW_POS=0 / READING_LIGHTS=OFF onResult=true, 중계 서버 SESSION_SHREDDED
```

## 4. 에뮬레이터에서 확인한 제약과 대응

| 현상 | 대응 |
| :-- | :-- |
| `DOOR_LOCK`이 좌석별 영역(0x4)을 거부 (`area ID: 0x4 not supported`) | `checkDoorLockCapabilities`가 돌려준 지원 영역(4도어 묶음) 중 조수석을 포함한 영역을 선택해 호출 |
| `WINDOW_POS` 범위가 0~10 (`value must be ≤ area max`) | `checkWindowPositionCapabilities`의 AreaBounds로 % → raw 환산 (25% → 2/10) |
| Pleos TTS ON_DEVICE 엔진 오류 (`ON_DEVICE_ENGINE_ERROR`), Android TTS 엔진도 미탑재 | `EventListener.onError` → `FallbackTtsEngine`이 Android TTS로 재시도 (에뮬레이터에서는 음성 출력 불가, 실차/엔진 탑재 환경 대상) |
| `SeatOccupancy` Boolean이 Virtual Console 설정과 반대로 전달 | 앱은 SDK 계약(true = 착좌)을 따름. 시연 시 콘솔을 반대로 조작. (다른 참가팀 저장소에서도 동일 보고) |
| 안전벨트 체결 상태를 Virtual Console로 바꿀 수 없음(경고등만) | 벨트 기반 자동 종료 대신 수동 [탑승 확인·종료] 경로로 시연. 자동 종료 로직은 유닛 테스트로 보장 |
| FusedLocation 좌표가 `adb emu geo fix`로는 들어오지 않음 (내비 시뮬레이션 전용) | 자동 접근·정차 판정은 Android Automotive 에뮬레이터 시뮬레이션 주행으로 시연. Pleos 에뮬레이터에서는 운전자 원탭 [간판 앞/정면/뒤] 경로 사용 |
| 차량 서비스가 화면 꺼짐 이후 응답하지 않는 경우 있음 (`Car.createCar() == null`) | 콜드 부팅으로 복구. 앱은 AAOS 차속 실패 시 FusedLocation 추정 차속으로 폴백 |
| `NaviHelper.requestRoute` 호출 시 내비 앱이 전면으로 전환 | 정상 동작(경로 설정). 시연에서는 앱을 다시 전면으로 호출 |

## 5. v3 (지도 중심 화면) 추가 검증 — 2026-11-14, 같은 에뮬레이터, `app-pleos-debug.apk`

앱은 Pleos 런처의 오른쪽 앱 영역(1707×1210 px)에 임베드되어 실행된다. 아래는 `tools/demo-video/pleos_flow.sh` 로 녹화한 시연에서 실제로 난 로그다
(원본: `pleos_emulator_logcat_v3.txt`, 중계 서버 타임라인: `pleos_emulator_relay_timeline_v3.jsonl` — 세션 시작 4.3초 → 동승자 위치 14.5초 → 승차 지점 이동 25.2초 → 제안 수락 41.8초 → 접근 44.9초 → P단 정차 49.2초 → 문 열기 56.8초 → 환대 65.8초 → 파기 74.0초, 단계 시각: `pleos_flow_taps_v3.json`).

| 확인 항목 | 결과 |
| :-- | :-- |
| OSM 지도 타일 로드 (osmdroid, `tile.openstreetmap.org`) | 에뮬레이터 네트워크로 로드됨. 하향등 ON 시 색 행렬 필터로 어두운 지도 |
| 동승자 위치 수신 | 동승자 웹(또는 `POST /api/v1/sessions/:id/passenger-location`) → 중계 SSE `PASSENGER_LOCATION` → 지도에 먹색 점 + 정확도 원, 연결 칩 "동승자 연결됨" |
| 동승자 간편 제안 | `POST /suggest` → SSE `PASSENGER_SUGGESTION` → IVI 카드 "지수님의 제안 / 2분만 기다려 주세요" → [수락] → 중계 상태 `suggestionReply=ACCEPTED` (타임라인 65.9초) |
| 여기 못 서요 → 앞으로 30 m | 승차 지점 이동(`pickupVersion` 1→2), `NaviHelper.requestRoute` 재호출(내비 앱 전면 전환 후 복귀), 상태 판에 "앞으로 30 m" |
| 지도에서 자리 고르기 → 여기서 탈게요 (v3.2) | 동승자 웹에서 지도를 움직여 핀을 맞추고 [여기서 탈게요] → `POST /suggest` `{kind:PICKUP_HERE, lat:37.499338, lng:127.062222}` → IVI 카드 "여기서 탈게요 / 승차 지점에서 92 m" → [수락] → 승차 지점이 그 좌표로 이동(`pickupVersion` 1→2, 라벨 "동승자 위치"), 동승자 웹 "운전자가 수락했어요" (세션 `mj-58098f41`) |
| 차량 위치 (디버그 adb 훅) | `am broadcast -a io.majung.pleos.DEBUG_LOCATION` → `MajungHub: [DEBUG adb] location(37.49880, 127.05800, heading=90.0)` → 지도 파란 원 마커, 거리 숫자 |
| 접근 감지 → P단 정차 | `접근 감지 · 반경 60m 감속 + 우측 방향지시등 진입 감지` → 기어 P + 차속 0 → `P단 정차 확정` → 중계 `phase=STEP4_CURBSIDE_STOPPED, stoppedInPark=true` |
| 하향등 ON → 밤 화면 | `lowBeam=ON` 콜백으로 밤 토큰(검은 판 → 흰 판, 지도 필터) 전환 |

```
MajungFlow: 주변 확인 완료 · 도어 잠금 해제 · 조수석 창문 25% 개방
MajungFlow: door.setDoorLock(ROW_1_LEFT+ROW_1_RIGHT+ROW_2_LEFT+ROW_2_RIGHT, value=false) -> onComplete(locked=false)
MajungFlow: window.setWindowPosition(ROW_1_RIGHT, raw=2 of 0..10) -> onComplete(20%)
MajungFlow: light.setReadingLightSwitch(ROW_1_RIGHT, ON) -> onComplete(ON)
MajungFlow: 착좌 감지 → 환대 인사: 지수님, 반갑습니다.
MajungFlow: 수동 탑승 확인 → 창문·조명·공조 원복 · 세션 즉시 파기          → 중계 SESSION_SHREDDED
```

런처 아이콘(v3.2): 심볼 '파란 판에 새긴 ㅈ'을 adaptive icon(`mipmap-anydpi-v26/ic_launcher.xml`, 배경 현대 블루 + 전경 흰 ㅈ 벡터)으로 넣었다. Pleos 런처 앱 목록에서 둥근 네모 마스크로 표시된다 (`docs/screenshots/pleos_launcher_icon.png`; `aapt2 dump badging` 에 `application-icon-*: res/mipmap-anydpi-v26/ic_launcher.xml`).

승차 지점 마커(v3.2): 차량 화면과 동승자 웹 모두 흰 판 위 현대 엠블럼으로 그린다(`ui/map/MajungMapView.kt`의 `drawPickup`, 웹의 `.mk-pick`). 동승자 웹에서 자리를 고르는 핀과 보낸 자리(운전자 답 대기)는 마중 심볼로 그려 차가 서는 자리와 구분한다. 엠블럼 경로는 원본 SVG의 evenodd 채우기 규칙을 그대로 써야 H 가 비어 보인다 — nonzero 로 그리면 타원이 통째로 채워진다(에뮬레이터·Chrome 양쪽에서 확인 후 수정). 스크린샷: `docs/screenshots/pleos_emulator_v3_approach.png`, `pleos_emulator_v3_suggestion.png`, `passenger_web_v3.png`.

접근 제어(v3.1): 중계 서버는 차량 키(`X-Majung-Car-Key`)와 동승자 토큰(`?t=`)을 세션 생성 시 받아 두고, 키 없는 차량 쪽 호출과 토큰 없는 동승자 쪽 호출을 403으로 거부한다. `node relay-server/verify_e2e_sse.mjs`가 허용·거부 경로를 모두 검증한다.

추가로 확인한 제약:
- `NaviHelper.requestRoute`(세션 시작·재경로)마다 호스트 내비 앱이 전면으로 온다. 시연 절차는 호출 뒤 `am start`로 마중 화면을 다시 앞으로 가져온다.
- Virtual Console의 차속은 0을 넣어도 서서히 줄어 `stoppedInPark`까지 7~14초가 걸린다. 시연 절차는 중계 상태의 `stoppedInPark`를 기다린다.
- 세션 시작 시 직전 좌표가 승차 지점 60 m 안에 남아 있으면 시작 직후 정차로 판정된다(단계는 뒤로 돌아가지 않음). 시연 절차는 시작 전에 320 m 서쪽, 기어 D로 둔다.

## 6. 재현 절차

```bash
# 1) 이미지 설치 (42dot SDK 라이선스 동의 필요)
./scripts/install_pleos_emulator_image.sh
# 2) AVD 생성·실행
avdmanager create avd -n pleos_connect -k "system-images;android-34;pleos_car_emulator;arm64-v8a" -d Pleos_Connect_v2_0
emulator -avd pleos_connect -no-snapshot -gpu host      # config.ini: hw.ramSize=4096, fastboot.forceColdBoot=yes
# 3) 앱 설치·권한
adb install -r -t apk/majung-v2-pleos-release.apk
adb shell pm grant io.majung.pleos android.car.permission.CAR_SPEED
# 4) Virtual Console (브라우저 UI 또는 HTTP API; API만 쓸 때는 1초마다 POST /browser/session?client=<id> 하트비트 필요)
adb forward tcp:8088 tcp:8088
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Park"
curl -X PUT "http://127.0.0.1:8088/vehicle/body?door_rl=true"
# 5) 로그
adb logcat -s MajungSDK:I MajungHub:I MajungFlow:I
# 6) v3 전체 시연(녹화 + 중계 타임라인 + 단계 시각): 디버그 APK 설치 후
adb install -r -t app/build/outputs/apk/pleos/debug/app-pleos-debug.apk
tools/demo-video/pleos_flow.sh <work-dir>
```

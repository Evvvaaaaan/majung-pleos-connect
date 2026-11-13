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

## 5. 재현 절차

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
```

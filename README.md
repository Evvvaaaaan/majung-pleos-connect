# 마중 (MAJUNG) — 폰은 모르고 차만 아는 3가지로 끝내는 안심 픽업

> 블라인드 심사 준수: 이 저장소에는 참가자의 성명·소속·연락처가 없습니다.

학원가·역 앞에서 "어디야? 무슨 간판 앞이야?"를 반복하는 마지막 100m를 **차량 신호**로 끝냅니다.
스마트폰 위치 공유가 할 수 없는 세 가지를 **Pleos Connect 차량용 앱**이 대신합니다.

| 폰은 모른다 | 차는 안다 (Vehicle SDK 신호) | 마중이 하는 일 |
| :-- | :-- | :-- |
| 차가 정말 멈췄는지 | `Gear = PARK` + 차속 0 | 그때서야 동승자 웹이 **"지금 타도 돼요"** 로 바뀐다 |
| 아이가 어느 문을 열었는지 | `DoorPosition(ROW_2_LEFT)` 열림 | 차도 쪽 문이면 **즉시 실내 음성 경고** |
| 안전벨트를 맸는지 | `SeatBeltBuckled` + 문 닫힘 | 공유 링크를 **자동 파기**하고 창문·조명 원복 |

운전자가 직접 누르는 버튼은 **"주변 확인 완료 · 승차 맞이" 하나**뿐이며, 그것도 P단 정차가 아니면 거부됩니다.
그 외 접근 알림·정차 확인·환대 인사·종료는 전부 차량 신호가 트리거합니다.

---

## 1. 6단계 흐름과 트리거 신호

| 단계 | 트리거 | 차량 → 동승자 웹 | 사용 API |
| :-- | :-- | :-- | :-- |
| 1 합의 | 운전자 "픽업 시작" (또는 Gleo 음성 `START_SAFE_PICKUP`) | 기준 간판·차종·번호판 끝 4자리·일회성 링크 | `NaviHelper.requestRoute(WithWaypoints)` |
| 2 접근 | 반경 80m + ≤25km/h + 우측 방향지시등 (또는 50m 저속 폴백) | "차가 들어오고 있어요" + 남은 거리(10m 단위) | `FusedLocation.registerFusedLocationCallback`, `TurnSignal.register…`, AAOS `PERF_VEHICLE_SPEED` |
| 3 식별 | — | 차종(VIN 앞 3자리 폴백) + 번호판 끝 4자리 상시 노출 | `CarInfo.getVINInfo` |
| 4 정차 | **기어 P + 차속 0** + 60m 이내 | "차가 멈췄어요" + 간판 기준 앞/뒤 m (신뢰도 ≥0.70일 때만) | `Gear.registerGearSelection`, `FusedLocation` confidence |
| 5 맞이 | 운전자 [주변 확인 완료] → 문 열림 → 착좌 | 도어 잠금 해제·창문 25%(우천 시 0%)·독서등·"○○님, 반갑습니다" | `Door.setDoorLock`, `Window.setWindowPosition`, `Light.setReadingLightSwitch`, `Seat.registerSeatOccupancy`, Pleos `TextToSpeech` |
| 6 종료 | **문 닫힘 + 벨트 체결** (센서 미지원 트림은 수동 버튼) | 링크 즉시 파기(`DELETE`), 20분 TTL 백업 | `Seat.registerSeatBeltBuckledState`, `Door.registerDoorPosition` |

안전 인터락 (항상 동작):
- 뒷좌석 차도 쪽(`ROW_2_LEFT`) 문 열림 → CRITICAL 음성 경고, DLI 게이트 우회
- 5단계 중 기어 D/R 전환 시 문 열림·벨트 미체결이면 출발 위험 경고
- 조향 각속도·브레이크·와이퍼·속도로 계산한 운전 부하 지수(DLI) ≥ 0.45 이면 일반 안내 보류(1.5초 안정 후 재생)
- 원격 도어 **개방**은 하지 않음. 잠금 해제만, P단 + 운전자 확인 후에만.

---

## 2. 코드가 실제로 하는 것 (README 주장 ↔ 소스 대응)

| 주장 | 파일 | 근거 |
| :-- | :-- | :-- |
| Vehicle SDK `register*` 콜백 13종 실제 등록 | `app/src/pleos/.../PleosRealSdkBindings.kt` `PleosLowLevelVehicleAdapter.registerTelemetryCallbacks` | gear·brake·steering·wiper(FRONT_WINDSHIELD)·turnSignal·door×4·seatOccupancy×3·seatBelt×3 + 초기값 `getCurrent*` 조회 |
| 차속은 AAOS 표준 속성 | `app/src/main/.../vehicle/AaosSpeedSource.kt` | `CarPropertyManager.registerCallback(PERF_VEHICLE_SPEED, SENSOR_RATE_UI)`; 실패 시 FusedLocation 좌표 변화로 추정 |
| 신호 → 상태 → 자동 전이 | `vehicle/VehicleSignalHub.kt` → `orchestrator/SignalDrivenPickupCoordinator.kt` | Hub가 StateFlow로 합치고, Coordinator가 edge-trigger로 6단계 전진 |
| 동승자 웹이 받는 차량 사실 | `model/MajungModels.kt` `VehicleFacts`, `relay-server/server.mjs` sanitize 목록 | 차량 원시 좌표는 서버가 버리고 10m 버킷 거리만 통과 |
| 능력 매트릭스 실제 조회 | `probeCapabilities()` | `checkSeatBeltBuckledCapabilities`, `checkSeatOccupancyCapabilities`, `checkDoorPositionCapabilities`, `checkDoorLockCapabilities`, `checkWindowPositionCapabilities` → 센서 없는 트림은 수동 종료 모드 |
| 시뮬레이션은 같은 경로 | `app/src/sim/.../SimSdkBindings.kt` `SimDriveScenario` | 리스너 인터페이스에 신호를 주입할 뿐, 앱 로직은 pleos 플레이버와 동일 |

**현재 범위에서 하지 않는 것**: 공조(HVAC)·앰비언트 제어, TBT(다음 안내 지점 거리) 연동, STT. 의존성에서도 제거했습니다.

---

## 3. 검증 (이 저장소에서 재현 가능)

```bash
node relay-server/verify_e2e_sse.mjs          # 중계 서버 E2E: GPS 필드 차단 · VehicleFacts 통과 · SSE · DELETE 파기
./gradlew :app:testSimDebugUnitTest           # 유닛 테스트 12건
./scripts/verify_and_build_apk.sh             # 위 두 가지 + sim/pleos release APK 생성
```

유닛 테스트가 보장하는 것(`app/src/test/.../SignalDrivenPickupCoordinatorTest.kt`):
- 멀리서 깜빡이만 켜면 접근 알림이 **안 나가고**, 80m·감속·우측 깜빡이가 모이면 나간다
- 차속 0이어도 **D단이면 4단계로 안 넘어가고**, P단이 되면 넘어가며 웹에 `stoppedInPark=true`가 실린다
- 주행 중 "승차 맞이"는 거부, P단에서는 잠금 해제 + 창문 25%, 우천 시 창문 0%
- 차도 쪽 문 열림 → 즉시 경고 발화
- 문 열림 → 독서등, 착좌 → 인사, 문 닫힘만으로는 종료 안 됨, **벨트 체결 → 자동 파기**
- 문 열린 채 D단 → 출발 위험 경고
- AAOS 차속이 없으면 FusedLocation 좌표로 36 km/h 추정

---

## 4. 실행

### 4-1. 중계 서버 + 동승자 웹 (Node 18+, 의존성 없음)
```bash
PORT=8080 node relay-server/server.mjs
# 에뮬레이터는 http://10.0.2.2:8080 으로 자동 연결, 브라우저는 http://localhost:8080/m/demo-session
```

### 4-2. Pleos Connect Emulator (API 34, Cold boot, RAM 4GB)
1. Android Studio(JDK 17)에서 루트 폴더 열기 → Build Variants `pleosRelease`
2. `apk/majung-v2-pleos-release.apk` 설치 → 앱 실행 시 `CAR_SPEED` 권한 허용
3. Virtual Console(127.0.0.1:8088)로 기어·도어·착좌·벨트 신호를 주입하면 화면 우측 "차량 신호" 패널과 동승자 웹이 즉시 반응

### 4-3. 일반 AVD (sim 플레이버)
`apk/majung-v2-sim-release.apk` 설치 후 화면 하단 **시뮬레이션** 행:
"주행 → 간판 앞 P단 정차" → "주변 확인 완료 · 승차 맞이" → "동승자 탑승(문·착좌·벨트)". 모두 리스너 주입이라 pleos와 같은 코드가 돈다.

---

## 5. 디자인

- **차량 화면**: Pleos(ccNC) 다크 IVI 언어 — 검정 바탕, 한 화면 한 메시지(40sp 헤드라인), 현대 브랜드 블루 계열 포인트, 환대 순간만 Sand(#E4DCD3) 카드. 터치 타깃 ≥ 56dp, UX 제한 시 입력란 숨김. 토큰: `ui/MajungTheme.kt`
- **동승자 웹**: 현대 브랜드 라이트 톤(Hyundai Blue #002C5F / Sand). 번호판 끝 4자리가 화면에서 가장 큰 요소. 상태 카드는 오직 차량 신호로만 바뀐다. `web-companion/index.html`
- 로고·전용 서체 등 상표 자산은 사용하지 않았습니다.

---

## 6. 구조

```text
app/src/main/kotlin/io/majung/pleos/
├── vehicle/VehicleSignalHub.kt          # SDK/AAOS/FusedLocation 콜백 → 단일 StateFlow (조향 각속도·추정 차속 계산)
├── vehicle/AaosSpeedSource.kt           # CarPropertyManager PERF_VEHICLE_SPEED
├── vehicle/PleosCapabilityGateway.kt    # 능력 매트릭스·도어/착좌/벨트 시퀀스 추론·출발 위험 판정
├── orchestrator/SignalDrivenPickupCoordinator.kt   # 신호 edge → 6단계 자동 전진 (유일한 수동: 주변 확인)
├── orchestrator/PickupEtaOrchestrator.kt           # 세션·티켓·VehicleFacts 발행·즉시 파기
├── safety/DrivingLoadGate.kt            # 운전 부하 지수 게이트
├── finder/CurbsideCarFinderEngine.kt    # 접근 복합 조건·간판 오프셋·거리
├── welcome/WelcomeHospitalityEngine.kt  # 환대 단계 결정
├── relay/EphemeralRelayClient.kt        # SSE/REST (OkHttp)
└── ui/MainActivity.kt · MajungTheme.kt  # Compose IVI 화면
app/src/pleos/…/PleosRealSdkBindings.kt  # Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TTS 3.3.1
app/src/sim/…/SimSdkBindings.kt          # 인메모리 구현 + 시나리오 재생기
relay-server/server.mjs · verify_e2e_sse.mjs
web-companion/index.html
```

SDK 버전은 `nexus-playground.pleos.ai`에 실제 배포된 것(2026-11-13 maven-metadata 기준)만 사용합니다.

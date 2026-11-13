# 마중 (MAJUNG) — Pleos Connect 차량-동승자 픽업 정합성 및 탑승 환대 앱

> **블라인드 심사 준수 안내**: 본 저장소(`README.md`, 소스 코드, 리소스, 매니페스트)에는 참가자의 성명·소속·연락처 등 개인 식별 정보를 포함하지 않습니다.

혼잡한 학원가나 역 앞에서 운전자와 동승자가 **같은 기준 간판을 합의하고, 차종과 번호판 끝 4자리로 내 차를 확인하며, 운전자의 주변 확인 후 안전하게 승차하고 즉시 위치 공유를 종료**하도록 돕는 **현대자동차그룹 Pleos Connect AAOS 네이티브 차량용 앱**입니다.

---

## 1. 공식 Pleos Connect 시작 가이드 준수 현황

공식 문서([Pleos Connect 앱 개발 시작하기](https://document.pleos.ai/docs/pleos-only/getting-started))의 Step 1~6 요구사항을 프로젝트 전반에 그대로 반영했습니다.

| 단계 | 공식 가이드 요구사항 | 구현 파일 및 검증 포인트 |
| :--- | :--- | :--- |
| **사전 준비 & Step 1** | Android SDK `API 34`, Kotlin, `minSdk = 28` 이상 Automotive Activity | `app/build.gradle.kts` (`compileSdk = 34`, `minSdk = 28`, `targetSdk = 34`) |
| **Step 2** | Pleos Connect Emulator (`API 34`, `Cold boot`, `RAM 4 GB`) | `pleos` 빌드 플레이버 기본 타깃 (오프라인 재현용 `sim` 플레이버 병행 제공) |
| **Step 3** | `settings.gradle.kts`에 `https://nexus-playground.pleos.ai/repository/maven-releases/` 등록 및 `Vehicle:2.3.1` 의존성 선언 | `settings.gradle.kts`, `gradle/libs.versions.toml` (`Vehicle:2.3.1`, `NaviHelper:3.0.5`, `FusedLocation:1.1.2`, `TextToSpeech:3.3.1`, `SpeechToText:3.3.1`) |
| **Step 4** | `AndroidManifest.xml`에 `pleos.car.permission.CAR_DOORS` 등 공개 차량 권한 선언 | `app/src/main/AndroidManifest.xml` (`PRIVATE_*` 비공개 권한 0건) |
| **Step 5** | `Vehicle(context)` -> `vehicle.initialize()` -> `getDoor().getCurrentDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), onComplete, onFailed)` 및 `onDestroy()`에서 `release()` 호출 | `MainActivity.kt` 진입 즉시 `runtimeBridge.queryStep5FrontLeftDoorPosition(...)` 실호출 및 `onDestroy()`에서 `runtimeBridge.release()` 해제 |
| **Step 6** | 에뮬레이터 실행 시 도어 위치 조회 결과 및 실패 예외 화면 표출 | IVI 상단 헤더(`Step 5 Door(ROW_1_LEFT)`) 및 하단 실시간 SDK 콜백 로그 바 상시 표시 (`[SDK 실연동]` / `[SIM 시뮬레이션]` 출처 구분 배지 포함) |

---

## 2. 6단계 핵심 사용자 흐름 및 안전·개인정보 방어 설계

1. **픽업 장소(기준 간판) 합의 및 일회성 링크 발급**: 내비게이션 목적지·경유지(`NaviHelper.requestRoute` / `requestRouteWithWaypoints`) 설정과 동시에 무설치 동승자 웹 링크를 생성합니다. 동승자 스마트폰의 원시 GPS 좌표는 수집·저장하지 않습니다(`collectPassengerGps: false`).
2. **차량 접근 안내 (오작동 방어 복합 조건)**: 단순 방향지시등 점등만으로는 발송하지 않으며, `반경 80m 이내 + 시속 25km 이하 감속 + 우측 방향지시등(TurnSignal)` 복합 조건 또는 `반경 50m 이내 저속 진입 폴백` 충족 시에만 접근 알림을 전환합니다.
3. **차종 및 번호판 끝 4자리 확인**: `CarInfo.getVINInfo()` 기반 차종 표기(미지원 시 운전자 입력 차종 폴백)와 운전자가 입력한 번호판 끝 4자리(`4568` 등)를 동승자 웹 최상단에 크게 표시합니다.
4. **조건부 간판 기준 정차 위치 안내 및 수동 오버라이드**: `FusedLocation(1.1.2)`의 `isSupported()` 및 `confidence >= 0.70`이 확보될 때만 간판 대비 대략적인 정차 오프셋(`앞 +12m` 등)을 계산하며, 지하주차장·빌딩숲 등 저신뢰 환경에서는 미터 표기를 자동 생략하고 운전자가 IVI 화면에서 `[간판 뒤(-10m) / 정면(0m) / 앞(+12m)]` 프리셋으로 즉시 지정할 수 있습니다.
5. **운전자 주변 확인 후 승차 맞이 및 착좌 환대(`"~~님, 반갑습니다."`)**: P단 정차 상태에서 운전자가 사이드미러로 후방 자전거·이륜차를 직접 확인한 뒤 `[주변확인 완료 · 승차맞이]`를 누르면 `Door.setDoorLock(ROW_1_RIGHT, value = false)`(잠금 해제)와 `Window.setWindowPosition(ROW_1_RIGHT, 25)`가 실행됩니다. 동승석 착좌(`Seat.getCurrentSeatOccupancy`)가 확인되면 센터 화면 환영 카드(`"지수님, 반갑습니다."`)와 함께 `Pleos TextToSpeech(Mode.ON_DEVICE, Locale.KOREAN)` 환영 음성이 재생됩니다.
6. **탑승 확인 및 공유 즉시 종료**: 도어 닫힘·안전벨트 체결(또는 미지원 트림용 `[탑승확인 · 공유 즉시종료]` 수동 버튼) 시 창문(`0%`)·실내 조명을 원복하고, `DELETE /api/v1/sessions/:id`를 호출해 공유 토큰을 서버 메모리에서 **즉시 파기**합니다(미종료 방치 시에도 발급 후 **최대 20분** 경과 시 자동 만료 파기).

---

## 3. 빠른 실행 방법 (3분 내 End-to-End 재현)

### 3-1. 무설치 동승자 웹 & 실시간 SSE 중계 서버 실행 (Node.js 18+)

외부 npm 패키지 설치 없이 표준 Node.js 내장 모듈만으로 즉시 동작합니다.

```bash
# 1) End-to-End 자동 검증 테스트 실행
node relay-server/verify_e2e_sse.mjs

# 2) 로컬 8080 포트로 중계 서버 기동 (에뮬레이터에서는 http://10.0.2.2:8080 으로 자동 연결됨)
PORT=8080 node relay-server/server.mjs
```

브라우저에서 `http://localhost:8080/m/demo-session` 접속 시 동승자 무설치 모바일 웹 화면을 바로 확인할 수 있습니다.

### 3-2. Android Studio 및 Pleos Connect Emulator 빌드·실행

1. **Android Studio (JDK 17, Android SDK API 34)** 에서 본 프로젝트 루트 폴더를 엽니다.
2. **Build Variants** 탭에서 실행 환경에 맞는 플레이버를 선택합니다:
   - `pleosDebug` / `pleosRelease`: **Pleos Connect Emulator (`API 34`, `Cold boot`, `RAM 4 GB`)** 실연동 모드 (`[SDK 실연동]` 배지 표시)
   - `simDebug` / `simRelease`: 일반 Android Emulator 오프라인 검증 모드 (`[SIM 시뮬레이션]` 배지 표시)
3. 터미널 원클릭 APK 생성:
   ```bash
   ./scripts/verify_and_build_apk.sh
   ```
   빌드 완료 시 `apk/majung-v2-pleos-release.apk` 및 `apk/majung-v2-sim-release.apk`로 자동 복사됩니다.

---

## 4. 디렉터리 구조

```text
majung-pleos-connect/
├── settings.gradle.kts                 # Pleos 공식 Maven 저장소 등록
├── gradle/libs.versions.toml           # Vehicle 2.3.1, NaviHelper 3.0.5, FusedLocation 1.1.2, TTS 3.3.1
├── app/
│   ├── build.gradle.kts                # API 34 / minSdk 28 / pleos & sim 투트랙 플레이버
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # 공개 차량 권한 선언 및 Gleo 인텐트 필터
│       │   ├── assets/                 # safe_pitstops_kr.json, Gleo schema.json
│       │   └── kotlin/io/majung/pleos/ # MainActivity(실연동 바인딩), 6단계 오케스트레이터, 오안내 방어 엔진
│       ├── pleos/kotlin/               # 공식 Pleos Connect SDK 실호출 어댑터 (PleosRuntimeBridgeImpl)
│       ├── sim/kotlin/                 # 일반 에뮬레이터 재현용 시뮬레이션 어댑터 (SimRuntimeBridgeImpl)
│       └── test/kotlin/                # 도메인·오케스트레이터 JUnit4 자동 검증 테스트
├── relay-server/
│   ├── server.mjs                      # 무의존성 실시간 SSE & 즉시 파기(최대 20분 TTL) 중계 서버
│   └── verify_e2e_sse.mjs              # SSE·GPS 필터링·간판 변경·DELETE 파기 E2E 자동 검증
├── web-companion/
│   └── index.html                      # Mobbin 모노크롬 동승자 무설치 안심 웹
└── scripts/
    ├── verify_and_build_apk.sh         # macOS/Linux 원클릭 테스트·APK 빌드 스크립트
    └── verify_and_build_apk.bat        # Windows 원클릭 테스트·APK 빌드 스크립트
```

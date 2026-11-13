package io.majung.pleos.model

/**
 * 마중(MAJUNG) v2.0 핵심 도메인 모델 (개정안_v2 기준 6단계 픽업 흐름 및 통일된 토큰·안전 기준)
 */
enum class PassengerProfile {
    ADULT,   // 일반 동승자: 역 앞·상가 간판 기준 정차 위치 합의 및 차종·번호판 끝 4자리 확인
    CHILD,   // 아동 동승자: 동승자 단말 GPS 미수집, 스쿨존 우회 후보 지점 안내, 카시트 좌석 열선 OFF 인터락
    SENIOR   // 어르신 동승자: 무장애 회차 후보 지점 우선 안내, 승차 시 실내 온기 및 완만한 출발 코칭
}

enum class RecipientChannel {
    ZERO_INSTALL_WEB, // 앱 설치 없이 브라우저에서 즉시 여는 일회성 안심 웹 링크
    KIDS_SMS          // 데이터 제한 단말용 70바이트 이하 단문 SMS 폴백
}

enum class SeatZone {
    ROW_1_LEFT,
    ROW_1_RIGHT,
    ROW_2_LEFT,
    ROW_2_RIGHT
}

enum class GearPosition {
    P, R, N, D
}

/**
 * 기획서 개정안 v2의 6단계 핵심 사용자 흐름과 1:1 매핑되는 세션 단계
 */
enum class PickupPhase {
    IDLE,                    // 대기
    STEP1_LANDMARK_AGREED,   // 1단계: 픽업 장소(간판·후보 포켓) 합의 및 일회성 링크 발급
    STEP2_APPROACHING,       // 2단계: 차량 접근 안내 (반경 80m + 저속 + 우측 방향지시등 복합 조건 또는 거리 기준 폴백)
    STEP3_VEHICLE_IDENTIFIED,// 3단계: 차종 및 번호판 끝 4자리(사용자 입력/설정값) 식별 안내
    STEP4_CURBSIDE_STOPPED,  // 4단계: 위치 신뢰 가능 시 간판 기준 오프셋 안내 / 저신뢰 시 수동 프리셋 안내
    STEP5_BOARDING_WELCOME,  // 5단계: 운전자 주변 확인 후 도어 언락·창문 25% 맞이 및 착좌 환대("~~님, 반갑습니다.")
    STEP6_COMPLETED_SHREDDED // 6단계: 탑승 확인·창문/조명 원복 및 외부 공유 토큰 즉시 파기 완료
}

enum class LocationReliability {
    HIGH_CONFIDENCE_AUTO,    // FusedLocation 신뢰도 양호: 간판 대비 대략적인 정차 위치(앞/뒤 m) 자동 산출
    LOW_CONFIDENCE_FALLBACK, // 지하주차장·빌딩숲·미지원 환경: 수치 오프셋 숨김 및 운전자 수동 선택 문구 표시
    DRIVER_MANUAL_OVERRIDE   // 운전자가 IVI 화면에서 [간판 뒤 / 정면 / 앞] 원탭으로 직접 지정
}

data class IntBounds(
    val minValue: Int,
    val maxValue: Int
) {
    fun clamp(requested: Int): Int = requested.coerceIn(minValue, maxValue)
}

data class VehicleCapabilityMatrix(
    val doorPositionSupported: Boolean = false,
    val doorLockWritable: Boolean = false,
    val windowPositionWritable: Boolean = false,
    val row1RightOccupancySupported: Boolean = false,
    val row2OccupancySupported: Boolean = false,
    val seatBeltBuckledSupported: Boolean = false,
    val seatHeaterSupported: Boolean = false,
    val seatHeaterWritable: Boolean = false,
    val hvacFanSupported: Boolean = false,
    val hvacFanWritable: Boolean = false,
    val hvacFanBoundsByZone: Map<SeatZone, IntBounds> = emptyMap(),
    val hvacRecirculationWritable: Boolean = false,
    val hvacTempWritable: Boolean = false,
    val readingLightWritable: Boolean = false,
    val ambientColorSupported: Boolean = false,
    val ambientColorWritable: Boolean = false,
    val steeringAngleSupported: Boolean = false,
    val brakePedalSupported: Boolean = false,
    val wiperStateSupported: Boolean = false
) {
    val boardingDetectionMode: BoardingDetectionMode
        get() = when {
            doorPositionSupported && (row1RightOccupancySupported || row2OccupancySupported) && seatBeltBuckledSupported ->
                BoardingDetectionMode.FULL_SENSOR_FUSION
            doorPositionSupported && seatBeltBuckledSupported ->
                BoardingDetectionMode.DOOR_AND_BELT_SEQUENCE_INFERENCE
            else ->
                BoardingDetectionMode.MANUAL_AND_VOICE_CONFIRMATION
        }
}

enum class BoardingDetectionMode {
    FULL_SENSOR_FUSION,               // 도어 개폐 + 착좌 센서 + 안전벨트 체결 통합 확인
    DOOR_AND_BELT_SEQUENCE_INFERENCE, // 착좌 센서 미지원 트림: 정차 중 도어 개폐(>=1.5s) + 45초 내 벨트 체결 추론
    MANUAL_AND_VOICE_CONFIRMATION     // 센서 미지원 또는 유아 카시트 환경: IVI [탑승 확인 · 마중 종료] 수동 확정
}

data class SafePitstopStop(
    val stopId: String,
    val passengerName: String,
    val profile: PassengerProfile,
    val targetSeat: SeatZone,
    val hasCarSeat: Boolean,
    val channel: RecipientChannel,
    val landmarkSignboard: String,
    val candidateSignboards: List<String>,
    val pitstopName: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String,
    val address: String,
    val rawPinWasInSchoolZone: Boolean,
    val walkingMetersWithoutCrossing: Int
)

/**
 * 일회성 픽업 공유 티켓 (개정안_v2 통일 규칙 반영):
 * - 기본 수명: 탑승 완료 또는 운전자 수동 종료 시 서버 메모리에서 즉시(DELETE) 파기
 * - 미종료 방치 시 최대 자동 만료(Max TTL): 발급 후 20분(1,200,000ms)
 * - 동승자 단말 원시 GPS 미수집(collectPassengerGps = false 고정)
 */
data class PickupSessionShareTicket(
    val sessionId: String,
    val oneTimeToken: String,
    val shareUrl: String,
    val vehicleModelLabel: String,
    val plateLast4: String,
    val landmarkSignboard: String,
    val candidateSignboards: List<String>,
    val offsetMeters: Int?,
    val offsetGuideText: String,
    val locationReliability: LocationReliability,
    val runtimeSourceBadge: String,
    val issuedAtEpochMs: Long,
    val maxExpiresAtEpochMs: Long = issuedAtEpochMs + MAX_SESSION_TTL_MS
) {
    fun buildCompactFallbackSms(etaMinutes: Int): String {
        val cleanPlate = plateLast4.takeLast(4)
        return "[마중] ${etaMinutes}분후 ${landmarkSignboard.take(10)} 앞 ${vehicleModelLabel.take(6)}(${cleanPlate})"
    }

    companion object {
        const val MAX_SESSION_TTL_MS: Long = 20L * 60L * 1000L // 최대 20분 자동 만료
    }
}

/**
 * "폰은 모르고 차만 아는 사실" — 동승자 웹과 IVI 양쪽에 그대로 전달되는 차량 신호 요약.
 * 스마트폰 위치 공유로는 알 수 없는 3가지(P단 정차 여부 · 어느 문이 열렸는지 · 안전벨트 체결 여부)가 핵심이다.
 */
data class VehicleFacts(
    val stoppedInPark: Boolean = false,        // 기어 P + 차속 ≤ 1 km/h: 이때만 "지금 타도 돼요"
    val trafficSideDoorOpen: Boolean = false,  // 차도 쪽(뒷좌석 좌측) 문 열림 → 즉시 경고
    val passengerDoorOpen: Boolean = false,    // 승차 좌석 문 열림
    val passengerSeated: Boolean = false,      // 승차 좌석 착좌 감지
    val passengerBuckled: Boolean = false,     // 승차 좌석 안전벨트 체결 → 공유 자동 종료 조건
    val gear: GearPosition = GearPosition.P,
    val speedBucketKmh: Int = 0,               // 5 km/h 단위 (웹 갱신 폭주 방지)
    val distanceBucketMeters: Int? = null,     // 10 m 단위
    val signalSource: String = ""              // "[SDK 실연동]" / "[SIM 시뮬레이션]"
) {
    companion object {
        fun bucketSpeed(speedKmh: Float): Int = ((speedKmh / 5f).toInt()) * 5
        fun bucketDistance(meters: Int?): Int? = meters?.let { (it / 10) * 10 }
    }
}

data class CabinActuatorSnapshot(
    val hvacFanSpeedByZone: Map<SeatZone, Int>,
    val hvacRecirculationOn: Boolean?,
    val hvacTempCelsiusByZone: Map<SeatZone, Float>,
    val seatHeaterLevelByZone: Map<SeatZone, Int>,
    val ambientArgbByZone: Map<SeatZone, Int>
)

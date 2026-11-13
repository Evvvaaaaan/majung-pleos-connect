package io.majung.pleos.finder

import io.majung.pleos.model.LocationReliability
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 차 밖에서 내 차 찾기 (Curbside Car-Finder) 코어 도메인 엔진 (개정안_v2 오안내 방어 기준 반영)
 *
 * 1) 접근 안내 오작동 방어: 단순 방향지시등 점등만으로는 발송하지 않으며,
 *    (목표 반경 <= 80m && 차속 <= 25km/h && 우측 방향지시등 ON) 복합 조건 충족 시에만 접근 알림 전환.
 *    방향지시등을 켜지 않더라도 반경 <= 50m & 저속 감속 시 거리 기준 폴백 안내로 누락 방지.
 * 2) 간판 오프셋 오안내 방어:
 *    - FusedLocation 지원 여부 및 신뢰도(confidence >= 0.70) 충족 시에만 내적 오프셋(앞/뒤 m) 자동 산출
 *    - 지하주차장·빌딩숲 등 저신뢰 환경에서는 수치 오프셋을 숨기고 차종·번호판 끝 4자리 + 텍스트 안내로 폴백
 *    - 운전자가 IVI 화면에서 [간판 뒤 -10m] / [간판 정면 0m] / [간판 앞 +12m] 수동 선택 시 즉시 우선 적용
 * 3) 차량 확인: CarInfo.getVINInfo 기반 차종 표기(미지원 시 사용자 설정 차종) + 번호판 끝 4자리(사용자 입력값)
 */
enum class PleosTurnSignalState {
    OFF,
    LEFT,
    RIGHT
}

enum class ManualOffsetPreset(val meters: Int, val labelKo: String) {
    BEHIND_10M(-10, "간판 뒤 (-10m)"),
    FRONT_0M(0, "간판 정면 (0m)"),
    AHEAD_12M(12, "간판 앞 (+12m)")
}

data class CurbsideEvaluationResult(
    val vehicleModelLabel: String,
    val licensePlateLast4: String,
    val approachTriggered: Boolean,
    val approachTriggerReason: String,
    val reliability: LocationReliability,
    val offsetMeters: Int?,
    val passengerGuideText: String
)

class CurbsideCarFinderEngine {

    fun shouldTriggerApproachNotice(
        distanceMeters: Int,
        speedKmh: Float,
        turnSignal: PleosTurnSignalState
    ): Pair<Boolean, String> {
        // 복합 조건: 반경 80m 이내 + 시속 25km 이하 감속 + 우측 방향지시등 점등
        if (distanceMeters <= 80 && speedKmh <= 25f && turnSignal == PleosTurnSignalState.RIGHT) {
            return true to "반경 ${distanceMeters}m 감속 + 우측 방향지시등 진입 감지"
        }
        // 미점등 대비 거리 기준 폴백: 반경 50m 이내 + 시속 20km 이하 진입 시 안내
        if (distanceMeters <= 50 && speedKmh <= 20f) {
            return true to "반경 ${distanceMeters}m 근접 감속 폴백 감지 (방향지시등 미점등 대비)"
        }
        return false to "접근 조건 대기 중 (일반 교차로 우회전 오작동 차단)"
    }

    /** 두 좌표 간 직선 거리(m). 접근 감지(반경 80m/50m) 및 정차 판정(≤ 60m) 입력으로 사용 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
        val dN = (lat2 - lat1) * metersPerDegLat
        val dE = (lng2 - lng1) * metersPerDegLng
        return kotlin.math.sqrt(dN * dN + dE * dE).roundToInt()
    }

    fun computeLongitudinalOffsetMeters(
        spotLat: Double,
        spotLng: Double,
        actualLat: Double,
        actualLng: Double,
        headingDeg: Double
    ): Int {
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * cos(Math.toRadians(spotLat))
        val deltaNorthM = (actualLat - spotLat) * metersPerDegLat
        val deltaEastM = (actualLng - spotLng) * metersPerDegLng

        val rad = Math.toRadians(headingDeg)
        val forwardNorth = cos(rad)
        val forwardEast = sin(rad)

        return (deltaNorthM * forwardNorth + deltaEastM * forwardEast).roundToInt()
    }

    fun formatReliableOffsetGuide(landmarkName: String, offsetMeters: Int): String {
        if (abs(offsetMeters) <= 3) {
            return "합의한 '${landmarkName}' 정면 연석 부근에 정차했습니다. 차종과 번호판 끝 4자리를 먼저 확인해 주세요."
        }
        val carLengths = (abs(offsetMeters) / 5.0).roundToInt().coerceAtLeast(1)
        return if (offsetMeters > 0) {
            "합의한 '${landmarkName}' 기준 차량 진행 방향 앞쪽 약 ${offsetMeters}m(차 ${carLengths}대 앞) 부근에 정차했습니다."
        } else {
            "합의한 '${landmarkName}' 못 미쳐 뒤쪽 약 ${abs(offsetMeters)}m(차 ${carLengths}대 뒤) 부근에 정차했습니다."
        }
    }

    fun evaluateCurbsideStop(
        vinCode: String?,
        fallbackModelName: String,
        plateLast4: String,
        landmarkName: String,
        spotLat: Double,
        spotLng: Double,
        actualLat: Double?,
        actualLng: Double?,
        headingDeg: Double?,
        locationConfidence: Double?,
        distanceMeters: Int,
        speedKmh: Float,
        turnSignal: PleosTurnSignalState,
        manualPreset: ManualOffsetPreset?
    ): CurbsideEvaluationResult {
        val modelLabel = resolveModelFromVin(vinCode, fallbackModelName)
        val (approachOk, approachReason) = shouldTriggerApproachNotice(distanceMeters, speedKmh, turnSignal)

        // 1순위: 운전자가 직접 [간판 뒤 / 정면 / 앞] 프리셋을 선택한 경우
        if (manualPreset != null) {
            return CurbsideEvaluationResult(
                vehicleModelLabel = modelLabel,
                licensePlateLast4 = plateLast4,
                approachTriggered = true,
                approachTriggerReason = "운전자 수동 정차 위치 확정",
                reliability = LocationReliability.DRIVER_MANUAL_OVERRIDE,
                offsetMeters = manualPreset.meters,
                passengerGuideText = formatReliableOffsetGuide(landmarkName, manualPreset.meters)
            )
        }

        // 2순위: FusedLocation 좌표 및 신뢰도(>= 0.70)가 확보된 경우에만 자동 오프셋 계산
        val conf = locationConfidence ?: 0.0
        if (actualLat != null && actualLng != null && headingDeg != null && conf >= MIN_RELIABLE_CONFIDENCE) {
            val rawOffset = computeLongitudinalOffsetMeters(spotLat, spotLng, actualLat, actualLng, headingDeg)
            // 반대편 차선이나 60m 초과 이탈 시 무리한 m 표기 대신 확인 요청 문구로 방어
            if (abs(rawOffset) <= 60) {
                return CurbsideEvaluationResult(
                    vehicleModelLabel = modelLabel,
                    licensePlateLast4 = plateLast4,
                    approachTriggered = approachOk,
                    approachTriggerReason = approachReason,
                    reliability = LocationReliability.HIGH_CONFIDENCE_AUTO,
                    offsetMeters = rawOffset,
                    passengerGuideText = formatReliableOffsetGuide(landmarkName, rawOffset)
                )
            }
        }

        // 3순위: 지하주차장·빌딩숲·저신뢰 환경 폴백 (무리한 미터 단정 배제)
        return CurbsideEvaluationResult(
            vehicleModelLabel = modelLabel,
            licensePlateLast4 = plateLast4,
            approachTriggered = approachOk,
            approachTriggerReason = approachReason,
            reliability = LocationReliability.LOW_CONFIDENCE_FALLBACK,
            offsetMeters = null,
            passengerGuideText = "주변 건물·지하 환경으로 세밀한 거리 오차 가능성이 있어 미터 표기를 생략했습니다. '${landmarkName}' 인근에서 ${modelLabel}(번호판 끝 4자리 ${plateLast4})를 확인해 주세요."
        )
    }

    fun resolveModelFromVin(vin: String?, fallbackModelName: String = "아이오닉 9"): String {
        if (vin.isNullOrBlank() || vin.length < 3) return fallbackModelName
        return when {
            vin.startsWith("KM8") -> "디 올 뉴 싼타페"
            vin.startsWith("KMH") -> "아이오닉 패밀리"
            vin.startsWith("KNA") || vin.startsWith("KN") -> "기아 패밀리 SUV"
            else -> fallbackModelName
        }
    }

    companion object {
        const val MIN_RELIABLE_CONFIDENCE = 0.70
    }
}

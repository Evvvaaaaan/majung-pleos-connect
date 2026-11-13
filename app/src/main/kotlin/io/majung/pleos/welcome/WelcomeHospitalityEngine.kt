package io.majung.pleos.welcome

/**
 * [마중(MAJUNG) 탑승 환대 시퀀스 엔진 — WelcomeHospitalityEngine]
 *
 * 운전자가 P단 정차 후 주변 안전을 직접 확인하고 승차 맞이를 실행할 때부터
 * 동승자 착좌 인사("~~님, 반갑습니다.") 및 탑승 완료 원복·토큰 즉시 파기까지의 상태를 관리합니다.
 */
enum class BoardingHospitalityStage {
    APPROACHING,
    CURBSIDE_EYE_CONTACT,
    DOOR_OPEN_LIGHTING,
    SEATED_WELCOME_GREETING,
    BUCKLED_READY_TO_DEPART
}

data class PassengerWelcomeProfile(
    val displayName: String,
    val personaType: String, // "ADULT", "CHILD", "SENIOR"
    val targetSeatArea: String, // "ROW_1_RIGHT", "ROW_2_RIGHT"
    val targetTempCelsius: Double = 22.5,
    val hasChildCarSeat: Boolean = false,
    val etaHomeMinutes: Int = 14
)

data class WelcomeUiAndHardwareDirective(
    val stage: BoardingHospitalityStage,
    val unlockPassengerDoorInPark: Boolean,
    val frontRightWindowPercent: Int,
    val readingLightOn: Boolean,
    val ambientBrightnessPercent: Int,
    val trafficSideDoorWarning: Boolean,
    val welcomeHeadline: String?,
    val welcomeSubtitle: String?,
    val ttsUtterance: String?,
    val burnLocationTokenImmediately: Boolean
)

class WelcomeHospitalityEngine {

    fun evaluateState(
        profile: PassengerWelcomeProfile,
        isParkGearZeroSpeed: Boolean,
        driverCheckedSurroundingsAndUnlocked: Boolean,
        isWiperActiveRain: Boolean,
        exteriorTempCelsius: Double,
        isNightLowBeamOn: Boolean,
        boardingDoorOpen: Boolean,
        trafficLeftDoorOpen: Boolean,
        seatOccupied: Boolean,
        seatBeltBuckledOrManualConfirmed: Boolean
    ): WelcomeUiAndHardwareDirective {
        // 1) 차도측(좌측) 도어 개방 시 최우선 안전 경고 발령
        if (trafficLeftDoorOpen) {
            return WelcomeUiAndHardwareDirective(
                stage = BoardingHospitalityStage.DOOR_OPEN_LIGHTING,
                unlockPassengerDoorInPark = false,
                frontRightWindowPercent = 0,
                readingLightOn = isNightLowBeamOn,
                ambientBrightnessPercent = 85,
                trafficSideDoorWarning = true,
                welcomeHeadline = "차도 방향(좌측) 문 열림 감지",
                welcomeSubtitle = "후방 이륜차·차량 접근 위험이 있습니다. 인도 쪽 우측 문을 이용해 주세요.",
                ttsUtterance = "주의하세요. 차도 방향 왼쪽 문이 열렸습니다. 인도 쪽 오른쪽 문을 이용해 주세요.",
                burnLocationTokenImmediately = false
            )
        }

        // 2) 모든 도어 닫힘 + 안전벨트 체결(또는 수동 확인) 완료 -> 창문·독서등 원복 및 공유 토큰 즉시 파기
        if (seatBeltBuckledOrManualConfirmed && !boardingDoorOpen) {
            return WelcomeUiAndHardwareDirective(
                stage = BoardingHospitalityStage.BUCKLED_READY_TO_DEPART,
                unlockPassengerDoorInPark = false,
                frontRightWindowPercent = 0,
                readingLightOn = false,
                ambientBrightnessPercent = 25,
                trafficSideDoorWarning = false,
                welcomeHeadline = "${profile.displayName}님 탑승 완료 · 공유 즉시 종료",
                welcomeSubtitle = "조수석 창문(0%)과 독서등을 주행 모드로 원복하고 일회성 위치 공유 세션을 즉시 파기했습니다.",
                ttsUtterance = "탑승과 안전 확인이 완료되어 외부 위치 공유를 종료했습니다. 목적지까지 편안하게 이동합니다.",
                burnLocationTokenImmediately = true
            )
        }

        val allowEyeContactWindow =
            isParkGearZeroSpeed && driverCheckedSurroundingsAndUnlocked && !isWiperActiveRain && exteriorTempCelsius > 0.0
        val targetWindowPct = if (allowEyeContactWindow) 25 else 0
        val safeDoorUnlock = isParkGearZeroSpeed && driverCheckedSurroundingsAndUnlocked

        // 3) 동승석 착좌 감지 -> "{탑승자명}님, 반갑습니다." 센터 디스플레이 환영 카드 + TTS 인사
        if (seatOccupied) {
            val cleanName = profile.displayName.trim().ifEmpty { "동승자" }
            val headline = "${cleanName}님, 반갑습니다."
            val subtitle = when (profile.personaType) {
                "CHILD" -> "오늘 수업도 수고 많았어요. 실내를 ${profile.targetTempCelsius}°C로 맞춰 두었으니 안전벨트를 매 주세요."
                "SENIOR" -> "천천히 편하게 앉으세요. 실내 온기를 포근하게 준비해 두었습니다."
                else -> "오늘 하루도 고생 많으셨어요. 실내 온도를 ${profile.targetTempCelsius}°C로 맞춰 두었습니다. 안전벨트를 매 주세요."
            }
            val tts = "${cleanName}님, 반갑습니다. $subtitle"

            return WelcomeUiAndHardwareDirective(
                stage = BoardingHospitalityStage.SEATED_WELCOME_GREETING,
                unlockPassengerDoorInPark = safeDoorUnlock,
                frontRightWindowPercent = targetWindowPct,
                readingLightOn = isNightLowBeamOn,
                ambientBrightnessPercent = 85,
                trafficSideDoorWarning = false,
                welcomeHeadline = headline,
                welcomeSubtitle = subtitle,
                ttsUtterance = tts,
                burnLocationTokenImmediately = false
            )
        }

        // 4) 승차 도어 물리 개방 -> 야간 독서등 점등 (문이 실제로 열렸을 때만 점등하여 정차 중 외부 노출 방지)
        if (boardingDoorOpen) {
            return WelcomeUiAndHardwareDirective(
                stage = BoardingHospitalityStage.DOOR_OPEN_LIGHTING,
                unlockPassengerDoorInPark = safeDoorUnlock,
                frontRightWindowPercent = targetWindowPct,
                readingLightOn = isNightLowBeamOn,
                ambientBrightnessPercent = 85,
                trafficSideDoorWarning = false,
                welcomeHeadline = "${profile.displayName}님이 탑승 중입니다.",
                welcomeSubtitle = "발밑과 안전벨트 버클이 잘 보이도록 승차 좌석 독서등을 비추고 있습니다.",
                ttsUtterance = null,
                burnLocationTokenImmediately = false
            )
        }

        // 5) 운전자가 P단 정차 상태에서 주변 확인 후 [승차 맞이]를 누른 상태
        if (safeDoorUnlock) {
            val weatherNote = if (isWiperActiveRain) {
                "우천(와이퍼 작동) 감지로 창문 개방은 자동 보류하고, 동승석 도어 잠금만 해제했습니다."
            } else {
                "운전자 주변 확인 후 동승석 도어 잠금을 해제하고 조수석 창문을 25% 내려 인사를 나눌 수 있습니다."
            }
            return WelcomeUiAndHardwareDirective(
                stage = BoardingHospitalityStage.CURBSIDE_EYE_CONTACT,
                unlockPassengerDoorInPark = true,
                frontRightWindowPercent = targetWindowPct,
                readingLightOn = false,
                ambientBrightnessPercent = 50,
                trafficSideDoorWarning = false,
                welcomeHeadline = "${profile.displayName}님 승차 맞이를 시작합니다.",
                welcomeSubtitle = weatherNote,
                ttsUtterance = null,
                burnLocationTokenImmediately = false
            )
        }

        return WelcomeUiAndHardwareDirective(
            stage = BoardingHospitalityStage.APPROACHING,
            unlockPassengerDoorInPark = false,
            frontRightWindowPercent = 0,
            readingLightOn = false,
            ambientBrightnessPercent = 25,
            trafficSideDoorWarning = false,
            welcomeHeadline = null,
            welcomeSubtitle = null,
            ttsUtterance = null,
            burnLocationTokenImmediately = false
        )
    }
}

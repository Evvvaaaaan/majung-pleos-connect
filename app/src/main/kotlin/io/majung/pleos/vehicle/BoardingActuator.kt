package io.majung.pleos.vehicle

/**
 * 승차 맞이에 쓰는 세 가지 차량 제어. pleos 플레이버는 Door/Window/Light SDK, sim 플레이버는 인메모리 구현.
 * 원격 자동 도어 "개방"은 하지 않는다 — 잠금 해제만 하며, 그것도 P단 정차 + 운전자 주변 확인 후에만 호출된다.
 */
interface BoardingActuator {
    fun setPassengerDoorUnlocked(unlocked: Boolean, onResult: (Boolean, String) -> Unit)
    fun setFrontPassengerWindowPercent(targetPercent: Int, onResult: (Int, String) -> Unit)
    fun setPassengerReadingLight(enabled: Boolean, onResult: (Boolean, String) -> Unit)
}

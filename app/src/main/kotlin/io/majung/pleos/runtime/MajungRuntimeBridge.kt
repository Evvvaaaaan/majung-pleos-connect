package io.majung.pleos.runtime

import io.majung.pleos.audio.PleosTtsEngine
import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.orchestrator.NaviRouteGateway
import io.majung.pleos.vehicle.BoardingActuator
import io.majung.pleos.vehicle.LowLevelVehicleHardware

data class VehicleLocationReading(
    val supported: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val headingDeg: Double?,
    val confidence: Double?,
    val rawSummary: String
)

/**
 * MainActivity가 빌드 플레이버(pleos 실연동 vs sim 시뮬레이션)와 무관하게
 * 동일한 생명주기(initialize -> API 호출 -> release)로 호출하는 통합 런타임 브리지 계약
 */
interface MajungRuntimeBridge : BoardingActuator {
    val runtimeBadgeLabel: String
    val usesRealPleosSdk: Boolean

    val lowLevelHardware: LowLevelVehicleHardware
    val naviGateway: NaviRouteGateway
    val ttsEngine: PleosTtsEngine

    /**
     * Activity.onCreate() 시점 초기화
     */
    fun initialize(onInitializedLog: (String) -> Unit)

    /**
     * 공식 시작 가이드(getting-started Step 5) 첫 번째 API 호출:
     * Vehicle.getDoor().getCurrentDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), ...)
     */
    fun queryStep5FrontLeftDoorPosition(onResult: (pos: Int?, logLine: String) -> Unit)

    /**
     * 차종 식별용 VIN 조회: Vehicle.getCarInfo().getVINInfo(...)
     */
    fun queryVehicleVin(onResult: (vin: String?, logLine: String) -> Unit)

    /**
     * 차량 FusedLocation(v1.1.2) 단발 위치 및 신뢰도 조회:
     * FusedLocation.isSupported() -> getLastKnownLocation(FusedLocationListener)
     */
    fun queryLastKnownVehicleLocation(onResult: (VehicleLocationReading) -> Unit)

    /**
     * 차량 FusedLocation 연속 수신 시작/중지:
     * FusedLocation.registerFusedLocationCallback(FusedLocationListener) / unregisterFusedLocationCallback
     * - 접근 감지(반경·감속·방향지시등 복합 조건)와 P단 정차 후 간판 오프셋 산출의 입력이 된다.
     */
    fun startLocationUpdates(onFix: (VehicleLocationReading) -> Unit)
    fun stopLocationUpdates()

    /**
     * 방향지시등 현재 상태 조회: Vehicle.getTurnSignal().getCurrentTurnSignalState(...)
     */
    fun queryTurnSignalState(onResult: (PleosTurnSignalState, String) -> Unit)

    /**
     * [sim 플레이버 전용] 실차·Pleos 에뮬레이터 없이도 6단계를 재현하는 시나리오 재생기.
     * pleos 플레이버는 false/no-op (실제 SDK 콜백만 사용).
     */
    val supportsSimulatedScenario: Boolean get() = false
    fun playSimulatedApproachAndStop(onLog: (String) -> Unit = {}) {}
    fun playSimulatedPassengerBoarding(onLog: (String) -> Unit = {}) {}
    fun playSimulatedTrafficSideDoorMistake(onLog: (String) -> Unit = {}) {}

    /**
     * P단 정차 & 운전자 주변 확인 후 동승석 도어 잠금/해제 제어:
     * Vehicle.getDoor().setDoorLock(DoorArea(ROW_1_RIGHT), value = false [언락] / true [잠금])
     */
    override fun setPassengerDoorUnlocked(unlocked: Boolean, onResult: (Boolean, String) -> Unit)

    /**
     * 조수석 창문 개방률 제어 (예: 25% 아이컨택 개방 / 탑승 완료 시 0% 원복):
     * Vehicle.getWindow().setWindowPosition(WindowArea(ROW_1_RIGHT), targetPercent)
     */
    override fun setFrontPassengerWindowPercent(targetPercent: Int, onResult: (Int, String) -> Unit)

    /**
     * 승차 도어 개방 시 독서등 제어:
     * Vehicle.getLight().setReadingLightSwitch(SeatArea(ROW_1_RIGHT), ...) / 시뮬레이션 대응
     */
    override fun setPassengerReadingLight(enabled: Boolean, onResult: (Boolean, String) -> Unit)

    /**
     * 동승석 착좌 여부 조회:
     * Vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(ROW_1_RIGHT))
     */
    fun queryPassengerSeatOccupancy(useRearRight: Boolean = false, onResult: (Boolean?, String) -> Unit)

    /**
     * Activity.onDestroy() 시점 리소스 해제 (콜백 진행 중 조기 호출 금지)
     */
    fun release()
}

package io.majung.pleos.vehicle

import io.majung.pleos.model.BoardingDetectionMode
import io.majung.pleos.model.CabinActuatorSnapshot
import io.majung.pleos.model.GearPosition
import io.majung.pleos.model.IntBounds
import io.majung.pleos.model.PassengerProfile
import io.majung.pleos.model.SeatZone
import io.majung.pleos.model.VehicleCapabilityMatrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LowLevelVehicleHardware {
    suspend fun initialize(): Boolean
    suspend fun probeCapabilities(): VehicleCapabilityMatrix

    suspend fun readDoorOpen(zone: SeatZone): Boolean?
    suspend fun readSeatOccupied(zone: SeatZone): Boolean?
    suspend fun readSeatBeltBuckled(zone: SeatZone): Boolean?
    suspend fun readSeatHeaterLevel(zone: SeatZone): Int?
    suspend fun writeSeatHeaterLevel(zone: SeatZone, level: Int): Boolean

    suspend fun readHvacFanSpeed(zone: SeatZone): Int?
    suspend fun writeHvacFanSpeed(zone: SeatZone, speed: Int): Boolean
    suspend fun readHvacRecirculationOn(): Boolean?
    suspend fun writeHvacRecirculationOn(recirculate: Boolean): Boolean
    suspend fun readHvacTempCelsius(zone: SeatZone): Float?
    suspend fun writeHvacTempCelsius(zone: SeatZone, tempC: Float): Boolean

    suspend fun readAmbientColorArgb(zone: SeatZone): Int?
    suspend fun writeAmbientColorArgb(zone: SeatZone, argb32: Int): Boolean

    fun registerTelemetryCallbacks(listener: VehicleTelemetryListener)
    fun unregisterTelemetryCallbacks(listener: VehicleTelemetryListener)
    fun release()
}

interface VehicleTelemetryListener {
    fun onSpeedChanged(speedKmh: Float)
    fun onGearChanged(gear: GearPosition)
    fun onSteeringAngleChanged(angleDeg: Float, rateDegPerSec: Float)
    fun onBrakePedalChanged(pressed: Boolean)
    fun onWiperHighChanged(isHighSpeed: Boolean)
    fun onDoorStateChanged(zone: SeatZone, isOpen: Boolean, timestampMs: Long)
    fun onSeatOccupancyChanged(zone: SeatZone, isOccupied: Boolean)
    fun onSeatBeltChanged(zone: SeatZone, isBuckled: Boolean, timestampMs: Long)
}

class PleosCapabilityGateway(
    private val hardware: LowLevelVehicleHardware
) {
    private val _capabilities = MutableStateFlow(VehicleCapabilityMatrix())
    val capabilities: StateFlow<VehicleCapabilityMatrix> = _capabilities.asStateFlow()

    private var preSessionSnapshot: CabinActuatorSnapshot? = null
    private val doorOpenedAtMs = mutableMapOf<SeatZone, Long>()
    private val doorValidDwellCompletedAtMs = mutableMapOf<SeatZone, Long>()
    private val latestDoorOpenState = mutableMapOf<SeatZone, Boolean>()
    private val latestSeatOccupiedState = mutableMapOf<SeatZone, Boolean>()
    private val latestSeatBeltState = mutableMapOf<SeatZone, Boolean>()

    suspend fun initializeAndProbe(): VehicleCapabilityMatrix {
        val connected = hardware.initialize()
        if (!connected) {
            val fallback = VehicleCapabilityMatrix()
            _capabilities.value = fallback
            return fallback
        }
        val probed = hardware.probeCapabilities()
        _capabilities.value = probed
        return probed
    }

    suspend fun capturePreSessionSnapshot(zones: Set<SeatZone> = setOf(SeatZone.ROW_1_LEFT, SeatZone.ROW_1_RIGHT, SeatZone.ROW_2_RIGHT)) {
        if (preSessionSnapshot != null) return
        val caps = _capabilities.value

        val fanMap = mutableMapOf<SeatZone, Int>()
        val tempMap = mutableMapOf<SeatZone, Float>()
        val heaterMap = mutableMapOf<SeatZone, Int>()
        val ambientMap = mutableMapOf<SeatZone, Int>()

        for (zone in zones) {
            if (caps.hvacFanSupported) hardware.readHvacFanSpeed(zone)?.let { fanMap[zone] = it }
            if (caps.hvacTempWritable) hardware.readHvacTempCelsius(zone)?.let { tempMap[zone] = it }
            if (caps.seatHeaterSupported) hardware.readSeatHeaterLevel(zone)?.let { heaterMap[zone] = it }
            if (caps.ambientColorSupported) hardware.readAmbientColorArgb(zone)?.let { ambientMap[zone] = it }
        }
        val recirc = if (caps.hvacRecirculationWritable) hardware.readHvacRecirculationOn() else null

        preSessionSnapshot = CabinActuatorSnapshot(
            hvacFanSpeedByZone = fanMap,
            hvacRecirculationOn = recirc,
            hvacTempCelsiusByZone = tempMap,
            seatHeaterLevelByZone = heaterMap,
            ambientArgbByZone = ambientMap
        )
    }

    suspend fun applyArrivalPreconditioning(
        profile: PassengerProfile,
        targetZone: SeatZone,
        hasCarSeat: Boolean,
        desiredFanLevel: Int = 3,
        desiredTempCelsius: Float = 22.5f
    ): PreconditioningOutcome {
        capturePreSessionSnapshot(setOf(SeatZone.ROW_1_LEFT, targetZone))
        val caps = _capabilities.value
        var carSeatHeaterLockedOff = false
        var appliedFanSpeed: Int? = null

        if (profile == PassengerProfile.CHILD && hasCarSeat) {
            if (caps.seatHeaterSupported && caps.seatHeaterWritable) {
                hardware.writeSeatHeaterLevel(targetZone, 0)
            }
            carSeatHeaterLockedOff = true
        }

        if (caps.hvacFanSupported && caps.hvacFanWritable) {
            val activeZone = if (caps.hvacFanBoundsByZone.containsKey(targetZone)) targetZone else SeatZone.ROW_1_LEFT
            val bounds: IntBounds? = caps.hvacFanBoundsByZone[activeZone]
            if (bounds != null) {
                val clamped = bounds.clamp(desiredFanLevel)
                if (hardware.writeHvacFanSpeed(activeZone, clamped)) {
                    appliedFanSpeed = clamped
                }
            }
        }

        if (caps.hvacTempWritable) {
            val safeTemp = if (profile == PassengerProfile.SENIOR) 23.5f else desiredTempCelsius
            hardware.writeHvacTempCelsius(SeatZone.ROW_1_LEFT, safeTemp)
        }

        return PreconditioningOutcome(
            appliedFanSpeed = appliedFanSpeed,
            carSeatHeaterForcedOff = carSeatHeaterLockedOff,
            usedSingleZoneFallback = !caps.hvacFanBoundsByZone.containsKey(targetZone)
        )
    }

    suspend fun activateFreshAirSmoothMode(): Boolean {
        val caps = _capabilities.value
        if (!caps.hvacRecirculationWritable) return false
        return hardware.writeHvacRecirculationOn(recirculate = false)
    }

    fun onDoorEvent(zone: SeatZone, isOpen: Boolean, timestampMs: Long, vehicleStopped: Boolean) {
        latestDoorOpenState[zone] = isOpen
        if (isOpen && vehicleStopped) {
            doorOpenedAtMs.putIfAbsent(zone, timestampMs)
        } else if (!isOpen) {
            val openedAt = doorOpenedAtMs.remove(zone)
            if (openedAt != null && (timestampMs - openedAt) >= MIN_DOOR_OPEN_DWELL_MS) {
                doorValidDwellCompletedAtMs[zone] = timestampMs
            }
        }
    }

    fun onSeatOccupancyEvent(zone: SeatZone, isOccupied: Boolean) {
        latestSeatOccupiedState[zone] = isOccupied
    }

    fun onSeatBeltEvent(zone: SeatZone, isBuckled: Boolean) {
        latestSeatBeltState[zone] = isBuckled
    }

    fun evaluateBoardingStatus(targetZone: SeatZone, nowMs: Long): BoardingEvaluation {
        val mode = _capabilities.value.boardingDetectionMode
        val doorOpen = latestDoorOpenState[targetZone] ?: false
        val beltBuckled = latestSeatBeltState[targetZone] ?: false

        return when (mode) {
            BoardingDetectionMode.FULL_SENSOR_FUSION -> {
                val occupied = latestSeatOccupiedState[targetZone] ?: false
                BoardingEvaluation(
                    mode = mode,
                    isDoorClosed = !doorOpen,
                    isPassengerSeatedOrInferred = occupied,
                    isSeatBeltBuckled = beltBuckled,
                    isReadyToDepartSafely = !doorOpen && occupied && beltBuckled
                )
            }
            BoardingDetectionMode.DOOR_AND_BELT_SEQUENCE_INFERENCE -> {
                val dwellClosedAt = doorValidDwellCompletedAtMs[targetZone]
                val inferredSeated = dwellClosedAt != null &&
                    (nowMs - dwellClosedAt) <= MAX_BELT_BUCKLE_WINDOW_MS
                BoardingEvaluation(
                    mode = mode,
                    isDoorClosed = !doorOpen,
                    isPassengerSeatedOrInferred = inferredSeated,
                    isSeatBeltBuckled = beltBuckled,
                    isReadyToDepartSafely = !doorOpen && inferredSeated && beltBuckled
                )
            }
            BoardingDetectionMode.MANUAL_AND_VOICE_CONFIRMATION -> {
                BoardingEvaluation(
                    mode = mode,
                    isDoorClosed = !doorOpen,
                    isPassengerSeatedOrInferred = false,
                    isSeatBeltBuckled = beltBuckled,
                    isReadyToDepartSafely = false
                )
            }
        }
    }

    fun checkDepartureHazard(targetZone: SeatZone, gear: GearPosition): String? {
        if (gear != GearPosition.D && gear != GearPosition.R) return null
        val doorOpen = latestDoorOpenState[targetZone] ?: false
        if (doorOpen) {
            return "경고! 동승석 도어가 아직 열려 있습니다. 정차 상태를 유지해 주세요."
        }
        if (_capabilities.value.seatBeltBuckledSupported) {
            val buckled = latestSeatBeltState[targetZone] ?: false
            val occupiedOrDwell = (latestSeatOccupiedState[targetZone] == true) ||
                doorValidDwellCompletedAtMs.containsKey(targetZone)
            if (occupiedOrDwell && !buckled) {
                return "동승자 안전벨트가 아직 체결되지 않았습니다. 벨트 확인 후 출발해 주세요."
            }
        }
        return null
    }

    suspend fun rollbackSessionStateAndRelease(releaseSdk: Boolean = false) {
        val snapshot = preSessionSnapshot
        val caps = _capabilities.value
        if (snapshot != null) {
            if (caps.hvacFanWritable) {
                snapshot.hvacFanSpeedByZone.forEach { (zone, speed) -> hardware.writeHvacFanSpeed(zone, speed) }
            }
            if (caps.hvacRecirculationWritable && snapshot.hvacRecirculationOn != null) {
                hardware.writeHvacRecirculationOn(snapshot.hvacRecirculationOn)
            }
            if (caps.hvacTempWritable) {
                snapshot.hvacTempCelsiusByZone.forEach { (zone, temp) -> hardware.writeHvacTempCelsius(zone, temp) }
            }
            if (caps.seatHeaterWritable) {
                snapshot.seatHeaterLevelByZone.forEach { (zone, level) -> hardware.writeSeatHeaterLevel(zone, level) }
            }
            if (caps.ambientColorWritable) {
                snapshot.ambientArgbByZone.forEach { (zone, argb) -> hardware.writeAmbientColorArgb(zone, argb) }
            }
            preSessionSnapshot = null
        }
        doorOpenedAtMs.clear()
        doorValidDwellCompletedAtMs.clear()
        if (releaseSdk) hardware.release()
    }

    companion object {
        const val MIN_DOOR_OPEN_DWELL_MS = 1_500L
        const val MAX_BELT_BUCKLE_WINDOW_MS = 45_000L
    }
}

data class PreconditioningOutcome(
    val appliedFanSpeed: Int?,
    val carSeatHeaterForcedOff: Boolean,
    val usedSingleZoneFallback: Boolean
)

data class BoardingEvaluation(
    val mode: BoardingDetectionMode,
    val isDoorClosed: Boolean,
    val isPassengerSeatedOrInferred: Boolean,
    val isSeatBeltBuckled: Boolean,
    val isReadyToDepartSafely: Boolean
)

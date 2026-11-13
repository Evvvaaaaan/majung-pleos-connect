package io.majung.pleos.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * 차속(PERF_VEHICLE_SPEED, m/s)은 Pleos Vehicle SDK 2.3.2에 없어 AAOS 표준 CarPropertyManager로 읽는다.
 * 권한 `android.car.permission.CAR_SPEED`는 런타임 권한이므로 MainActivity가 먼저 요청한다.
 * 실패하면 false를 반환하고, 허브는 FusedLocation 좌표 변화로 차속을 추정한다.
 */
class AaosSpeedSource(private val context: Context) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null

    fun start(onSpeedKmh: (Float) -> Unit, onLog: (String) -> Unit): Boolean {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            onLog("AAOS CAR_SPEED 권한 미승인 → FusedLocation 추정 차속 사용")
            return false
        }
        return runCatching {
            val c = Car.createCar(context) ?: error("Car.createCar() == null")
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val mps = (value.value as? Float) ?: return
                    onSpeedKmh(kotlin.math.abs(mps) * 3.6f)
                }

                override fun onErrorEvent(propId: Int, zone: Int) {
                    onLog("AAOS PERF_VEHICLE_SPEED 오류(prop=$propId)")
                }
            }
            if (m.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) == null) error("PERF_VEHICLE_SPEED 미지원")
            check(m.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)) { "registerCallback 실패" }
            car = c
            manager = m
            callback = cb
            onLog("AAOS CarPropertyManager PERF_VEHICLE_SPEED 구독 (SENSOR_RATE_UI)")
            true
        }.getOrElse { e ->
            onLog("AAOS 차속 구독 실패(${e.message}) → FusedLocation 추정 차속 사용")
            false
        }
    }

    fun stop() {
        runCatching { callback?.let { manager?.unregisterCallback(it) } }
        runCatching { car?.disconnect() }
        callback = null
        manager = null
        car = null
    }

    companion object {
        const val PERMISSION = "android.car.permission.CAR_SPEED"
    }
}

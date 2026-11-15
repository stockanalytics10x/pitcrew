package com.example.washguard

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.VehicleGear
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Drive/park from the car: parked only when the gear is P and the system does not require distraction optimisation.
 * Fails closed: UNKNOWN on startup, disconnect or unavailable automotive services (screens then show the driving layout).
 * Runs as long as the Pit Wall runs (not tied to a screen), so Box calls keep working behind the navi.
 */
class DriveAccessMonitor(private val context: Context) {
    var access by mutableStateOf(DriveAccess.UNKNOWN)
        private set
    var onChange: (DriveAccess) -> Unit = {}

    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var driving: CarPropertyManager? = null
    private var gearCallback: CarPropertyManager.CarPropertyEventCallback? = null
    private var parked = false
    private var restricted = true
    private var generation = 0

    private fun set(a: DriveAccess) { access = a; onChange(a) }
    private fun publish() = set(if (restricted || !parked) DriveAccess.RESTRICTED else DriveAccess.PARKED)

    fun start() {
        if (car != null) return
        val token = ++generation
        runCatching {
            car = Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (token != generation) return@createCar
                if (!ready) { restricted = true; parked = false; set(DriveAccess.UNKNOWN); return@createCar }
                runCatching {
                    val u = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    val p = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    ux = u; driving = p
                    restricted = u.currentCarUxRestrictions.isRequiresDistractionOptimization
                    parked = p.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) == VehicleGear.GEAR_PARK
                    u.registerListener { if (token == generation) { restricted = it.isRequiresDistractionOptimization; publish() } }
                    val callback = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(value: CarPropertyValue<*>) {
                            if (token == generation) {
                                parked = value.status == CarPropertyValue.STATUS_AVAILABLE && value.value == VehicleGear.GEAR_PARK
                                publish()
                            }
                        }
                        override fun onErrorEvent(propertyId: Int, areaId: Int) { if (token == generation) { parked = false; publish() } }
                    }
                    gearCallback = callback
                    if (!p.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) parked = false
                    publish()
                }.onFailure { set(DriveAccess.UNKNOWN) }
            }
        }.onFailure { set(DriveAccess.UNKNOWN) }
    }

    fun stop() {
        generation++
        runCatching { ux?.unregisterListener() }
        runCatching { gearCallback?.let { driving?.unregisterCallback(it) } }
        runCatching { car?.disconnect() }
        ux = null; driving = null; car = null; gearCallback = null; parked = false; restricted = true
        set(DriveAccess.UNKNOWN)
    }
}

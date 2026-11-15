package com.example.washguard

import android.content.Context
import android.content.pm.PackageManager

/**
 * The Pit Wall's parts for the whole app process: car/navi/voice clients and the session.
 * The foreground service keeps them running while the screens are in the background; the screens just show them.
 */
class PitWallRuntime private constructor(private val app: Context) {
    val navi = NaviClient(app)
    val vehicleData = VehicleDataClient(app)
    private val travelStore = TravelStore(app)
    private val keyStore = ApiKeyStore(app)
    val travel = TravelClient(app, travelStore, keyStore)
    val weather = WeatherClient(app, travelStore, keyStore) { travel.state.lastWash }
    val range = RangeClient(app)
    val energyLog = EnergyLog(app)
    val driveRecords = DriveRecordStore(app)
    val health = VehicleHealthClient(app)
    val washModeController = WashModeController(app).apply { initialize() }
    val boxCall: BoxCallController = BoxCallController(PitVoice(app).apply { initialize() }) { navi.openSearch(pit.powertrain.searchKind) }
    val demo: DemoController = DemoController(onAnnounce = { boxCall.announce(it) }, onDrivingScene = { boxCall.resetMemory() })
    val driveAccess = DriveAccessMonitor(app)
    val pit: PitWallSession = PitWallSession(app, { vehicleData.state }, { range.state }, { weather.state },
        navi = navi, energyLog = energyLog, demo = demo, health = health, boxCall = boxCall, driveRecords = driveRecords)

    /** True while a screen of the app is on display. */
    var uiVisible = false
    private var running = false

    init {
        travel.onLocation = weather::useLocation
        travel.onClearAll = { energyLog.clear(); driveRecords.clear() }
        driveAccess.onChange = { pit.realAccess = it }
        demo.powertrainOf = { pit.powertrain }
    }

    /** Start reading the car, navi and weather. Safe to call more than once. */
    fun startCore() {
        if (running) return
        running = true
        driveAccess.start()
        health.start()
        navi.start()
        vehicleData.start()
        weather.start()
        if (app.checkSelfPermission(RANGE_PERMISSION) == PackageManager.PERMISSION_GRANTED) range.start()
    }

    fun stopCore() {
        if (!running) return
        running = false
        driveAccess.stop()
        health.stop()
        navi.stop()
        vehicleData.stop()
        weather.stop()
        range.stop()
    }

    companion object {
        const val RANGE_PERMISSION = "android.car.permission.CAR_ENERGY"
        @Volatile private var instance: PitWallRuntime? = null
        fun get(context: Context): PitWallRuntime =
            instance ?: synchronized(this) { instance ?: PitWallRuntime(context.applicationContext).also { instance = it } }
    }
}

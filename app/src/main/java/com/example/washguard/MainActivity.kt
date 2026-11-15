
package com.example.washguard

import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var rt: PitWallRuntime
    private val vehicleData get() = rt.vehicleData
    private val weather get() = rt.weather
    private val range get() = rt.range
    private val navi get() = rt.navi
    private val travel get() = rt.travel
    private val washModeController get() = rt.washModeController
    private val boxCall get() = rt.boxCall
    private val pit get() = rt.pit
    private val health get() = rt.health
    private lateinit var liveDrive: LiveDriveRepository
    private lateinit var speedTelemetry: SpeedTelemetryClient
    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val requestNaviPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        android.util.Log.i("PitstopNavi", "permission result $result")
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            navi.retry()
            if (result[rangePermission] == true) { range.stop(); range.start() }
            if (result["pleos.car.permission.CAR_SAFETY"] == true || result["pleos.car.permission.PRIVATE_CAR_SAFETY"] == true) { health.stop(); health.start() }
            if (result["pleos.car.permission.TTS_SERVICE"] == true || result["pleos.car.permission.STT_SERVICE"] == true) boxCall.restartVoice()
            if (result[android.Manifest.permission.RECORD_AUDIO] == true) PitWallService.start(this)
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { travel.enable(); weather.useVehicle(travel.state.current) }
    }
    private val requestSpeedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            liveDrive.begin(); speedTelemetry.start()
        } else liveDrive.gap("차량 속도 권한이 필요해요 · 주차 후 다시 시작")
    }
    private fun beginSpeedRecording() {
        if (checkSelfPermission(SpeedTelemetryClient.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            speedTelemetry.stop(); liveDrive.begin(); speedTelemetry.start()
        } else requestSpeedPermission.launch(SpeedTelemetryClient.PERMISSION)
    }
    private val rangePermission = "android.car.permission.CAR_ENERGY"
    private var permissionTick by androidx.compose.runtime.mutableIntStateOf(0)
    private var lastAsked: String? = null
    /** One permission from the settings page. If no system dialog can be shown, open the app's permission settings. */
    private val requestOne = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val perm = lastAsked
        permissionTick++
        if (granted) {
            when (perm) {
                rangePermission -> { range.stop(); range.start() }
                "pleos.car.permission.TTS_SERVICE", "pleos.car.permission.STT_SERVICE", android.Manifest.permission.RECORD_AUDIO -> { boxCall.restartVoice(); PitWallService.start(this) }
                in NaviClient.PERMISSIONS -> navi.retry()
                "pleos.car.permission.CAR_SAFETY", "pleos.car.permission.PRIVATE_CAR_SAFETY" -> { health.stop(); health.start() }
            }
        } else if (perm != null && !shouldShowRequestPermissionRationale(perm)) {
            runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)))
            }
        }
    }
    private val requestRangePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) range.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = PitWallRuntime.get(this)
        liveDrive = LiveDriveRepository(applicationContext)
        speedTelemetry = SpeedTelemetryClient(applicationContext, liveDrive)
        // Keep the Pit Wall running behind the navi (Box calls, warning lights). Stops from its notification.
        PitWallService.start(this)
        // Ask once at start for everything the Pit Wall reads: navi route, vehicle range, and the mic for Box calls.
        val startupMissing = (NaviClient.PERMISSIONS + rangePermission + android.Manifest.permission.RECORD_AUDIO + "pleos.car.permission.TTS_SERVICE" + "pleos.car.permission.STT_SERVICE" + "pleos.car.permission.CAR_SAFETY" + "pleos.car.permission.PRIVATE_CAR_SAFETY" +
            (if (android.os.Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()))
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (startupMissing.isNotEmpty()) requestNaviPermissions.launch(startupMissing.toTypedArray())

        setContent {
            PitstopApp(pit, weather::refresh,
                driveRepository = liveDrive, onStartTelemetry = ::beginSpeedRecording, washController = washModeController,
                permissionTick = permissionTick, onRequestPermission = { lastAsked = it; requestOne.launch(it) },
                travelClient = travel, weatherClient = weather, onStartRecording = {
                    if (checkSelfPermission(TravelClient.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                        travel.stop(); weather.useVehicle(travel.state.current); travel.enable()
                    } else locationPermission.launch(TravelClient.PERMISSION)
                }, onConnectRange = {
                if (checkSelfPermission(rangePermission) == PackageManager.PERMISSION_GRANTED) {
                    range.stop()
                    range.start()
                } else requestRangePermission.launch(rangePermission)
            })
        }
    }

    override fun onStart() {
        super.onStart()
        rt.uiVisible = true
        permissionTick++
        rt.startCore()
        travel.start()
        if (liveDrive.recording && checkSelfPermission(SpeedTelemetryClient.PERMISSION) == PackageManager.PERMISSION_GRANTED) speedTelemetry.start()
    }

    override fun onStop() {
        rt.uiVisible = false
        speedTelemetry.stop()
        travel.stop()
        // The service keeps the Pit Wall running; without it, stop reading the car like before.
        if (!PitWallService.running) rt.stopCore()
        super.onStop()
    }

    override fun onDestroy() {
        // Pit Wall parts belong to the process (and the service), not to this screen.
        speedTelemetry.close()
        super.onDestroy()
    }
}

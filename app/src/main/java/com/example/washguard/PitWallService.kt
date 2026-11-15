package com.example.washguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the Pit Wall running while the app is behind the navi: reads the car, watches the plan and makes Box calls.
 * Shows one quiet notification with an off switch. Does not control the car.
 */
class PitWallService : Service() {
    private lateinit var rt: PitWallRuntime

    override fun onCreate() {
        super.onCreate()
        // Foreground first: Android kills the app if this takes more than a few seconds,
        // and building the runtime (car and navi connections) can be slow on a cold start.
        goForeground()
        running = true
        rt = PitWallRuntime.get(this)
        rt.startCore()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        goForeground() // again after a permission change, so the mic type is added once granted
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pit Wall", NotificationManager.IMPORTANCE_LOW).apply {
                description = "주행 중 배터리·경로·경고등을 지켜보고 무전을 보내요"
            })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val off = PendingIntent.getService(this, 1, Intent(this, PitWallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pit Wall 대기 중")
            .setContentText("주행 중 충전·주유·경고를 지켜보고 필요할 때 무전해요")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "끄기", off).build())
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            } else startForeground(ID, n)
        }.onFailure { Log.w(TAG, "foreground start failed", it); status = "백그라운드 실행 실패: ${it.message ?: it.javaClass.simpleName}" }
            .onSuccess { status = "백그라운드 실행 중" }
    }

    override fun onDestroy() {
        running = false
        status = "꺼짐"
        if (::rt.isInitialized && !rt.uiVisible) rt.stopCore()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PitWallService"
        private const val CHANNEL = "pit_wall"
        private const val ID = 7
        private const val ACTION_STOP = "com.example.washguard.PIT_WALL_STOP"
        @Volatile var running = false
            private set
        /** Shown in SETTINGS. */
        @Volatile var status = "시작 전"
            private set

        fun start(context: Context) {
            runCatching {
                val i = Intent(context, PitWallService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            }.onFailure { Log.w(TAG, "start failed", it); status = "시작 실패: ${it.message ?: it.javaClass.simpleName}" }
        }
    }
}

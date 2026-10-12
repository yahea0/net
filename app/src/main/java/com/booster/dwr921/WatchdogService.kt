package com.booster.dwr921

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class WatchdogService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var isRunning = false
    private lateinit var controller: DwrController
    private var failCount = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra("ROUTER_IP") ?: "192.168.0.1"
        val pass = intent?.getStringExtra("ROUTER_PASS") ?: "admin01"

        controller = DwrController(ip, pass) { msg -> broadcastLog(msg) }

        createNotification()
        startWatchdogLoop()

        return START_STICKY
    }

    private fun createNotification() {
        val channelId = "DWR_ANWEB_WATCHDOG"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "حارس اتصال Anweb DWR-921",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🛡️ حارس اتصال DWR-921 قيد التشغيل")
            .setContentText("يراقب الجلسة باستمرار، وينعش النت تلقائياً عند انقطاعه...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun startWatchdogLoop() {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            // تسجيل الدخول لأول مرة
            controller.authenticateAnweb()

            while (isActive && isRunning) {
                controller.pumpStabilityPulse()
                val isAlive = controller.isInternetAlive()

                if (!isAlive) {
                    failCount++
                    if (failCount >= 2) {
                        broadcastLog("⚠️ تم رصد انقطاع النت! جاري إعادة إحياء الجلسة فوراً...")
                        controller.reviveWanSession()
                        delay(5000)
                        failCount = 0
                    }
                } else {
                    failCount = 0
                }
                delay(2000)
            }
        }
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent("DWR_LOG_EVENT").apply {
            setPackage(packageName)
            putExtra("LOG_TEXT", msg)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

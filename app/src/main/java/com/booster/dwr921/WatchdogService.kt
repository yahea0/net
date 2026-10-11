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
        val pass = intent?.getStringExtra("ROUTER_PASS") ?: ""

        controller = DwrController(ip, pass) { /* logs handled via broadcast */ }

        createNotification()
        startWatchdogLoop()

        return START_STICKY
    }

    private fun createNotification() {
        val channelId = "DWR_WATCHDOG_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "حارس الاتصال DWR-921",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🛡️ حارس اتصال DWR-921 قيد العمل")
            .setContentText("يراقب الاتصال باستمرار، ويمنع انقطاع النت وتذبذب التردد...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        startForeground(101, notification)
    }

    private fun startWatchdogLoop() {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            while (isActive && isRunning) {
                // 1. إرسال نبضة منع ذبذبة وتثبيت التردد (Cell_DCH Lock)
                controller.pumpActiveChannel()

                // 2. فحص هل الإنترنت شغال حقيقة؟
                val alive = controller.isInternetAlive()

                if (!alive) {
                    failCount++
                    // لو فشل النت لمرتين متتاليتين (انقطع النت المفاجئ)
                    if (failCount >= 2) {
                        broadcastLog("⚠️ انقطع النت فجأة! جاري إيقاظ الراوتر وإعادة ربط الشبكة تلقائياً...")
                        controller.reviveConnection()
                        delay(6000) // إعطاء الراوتر مهلة لتجديد الـ IP
                        failCount = 0
                    }
                } else {
                    failCount = 0
                }

                delay(2000) // فحص دوري كل ثانيتين
            }
        }
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent("DWR_LOG_EVENT")
        intent.putExtra("LOG_TEXT", msg)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

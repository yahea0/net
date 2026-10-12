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
    private var consecutiveFails = 0

    companion object {
        const val CHANNEL_ID = "DWR_ANWEB_WATCHDOG"
        const val NOTIFICATION_ID = 101
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = intent?.getStringExtra("ROUTER_IP")?.takeIf { it.isNotBlank() } ?: "192.168.0.1"
        val pass = intent?.getStringExtra("ROUTER_PASS")?.takeIf { it.isNotBlank() } ?: "admin"

        controller = DwrController(ip, pass) { msg -> broadcastLog(msg) }

        createNotificationChannel()
        updateNotification("🛡️ الحارس الذكي نشط: يراقب اتصال رامي ليفي...")
        startWatchdogEngine()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "حارس اتصال Anweb DWR-921",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مراقبة وإعادة إنعاش اتصال رامي ليفي تلقائياً"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(statusText: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ حارس اتصال رامي ليفي (DWR-921)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startWatchdogEngine() {
        if (isRunning) return
        isRunning = true

        serviceScope.launch {
            broadcastLog("🚀 بدأ تشغيل الحارس التلقائي: فحص كل 3 ثوانٍ ونبضات تثبيت الاتصال مفعلة.")
            controller.authenticateAnweb()

            while (isActive && isRunning) {
                // إرسال نبضة منع خمول البرج (Anti-Idle Pulse)
                controller.pumpStabilityPulse()

                // قياس البنج المباشر
                val ping = controller.measureInternetPing()

                if (ping != -1L) {
                    consecutiveFails = 0
                    updateNotification("🟢 النت متصل ومستقر | البنج: ${ping}ms")
                } else {
                    consecutiveFails++
                    updateNotification("⚠️ تعثر الاتصال (محاولة $consecutiveFails من 2)...")
                    broadcastLog("⚠️ رصد بطء أو انقطاع في الاستجابة ($consecutiveFails/2)")

                    if (consecutiveFails >= 2) {
                        broadcastLog("🚨 تأكد انقطاع النت! جاري تفعيل زر [RECONNECT] في الراوتر لإحياء الجلسة...")
                        updateNotification("⚡ جاري إعادة تشغيل الشبكة وإنعاش الاتصال...")

                        val revived = controller.reviveWanSession()
                        if (revived) {
                            broadcastLog("⏳ تم تمرير الإنعاش، ننتظر 6 ثوانٍ لاستقرار الـ IP الجديد...")
                            delay(6000)
                        } else {
                            delay(3000)
                        }
                        consecutiveFails = 0
                    }
                }
                delay(3000)
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

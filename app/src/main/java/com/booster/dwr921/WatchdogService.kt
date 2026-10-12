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
        updateNotification("🛡️ حارس 3G نشط: يراقب اتصال رامي ليفي...")
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
                description = "مراقبة وإنعاش اتصال رامي ليفي تلقائياً"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(statusText: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ حارس رامي ليفي 3G (DWR-921)")
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
            broadcastLog("🚀 بدأ تشغيل الحارس: نبضات تثبيت 3G مفعلة وفحص الاتصال كل 2.5 ثانية.")
            controller.authenticateAnweb()

            while (isActive && isRunning) {
                // إرسال نبضة منع خمول برج 3G
                controller.pumpStabilityPulse()

                // قياس البنج الحقيقي
                val ping = controller.measureInternetPing()

                if (ping != -1L) {
                    consecutiveFails = 0
                    updateNotification("🟢 النت مستقر | البنج: ${ping}ms (3G HSPA+)")
                    broadcastPing(ping)
                } else {
                    consecutiveFails++
                    updateNotification("⚠️ تعثر الاستجابة (محاولة $consecutiveFails من 2)...")
                    broadcastPing(-1L)
                    broadcastLog("⚠️ تعثر استجابة شبكة 3G ($consecutiveFails/2)")

                    if (consecutiveFails >= 2) {
                        broadcastLog("🚨 تأكد انقطاع النت! جاري تفعيل زر RECONNECT لإحياء الجلسة فوراً...")
                        updateNotification("⚡ جاري إعادة إنعاش الشبكة وطلب IP جديد...")

                        val revived = controller.reviveWanSession()
                        if (revived) {
                            broadcastLog("⏳ تم الإنعاش، ننتظر 5 ثوانٍ لاستقرار بيانات رامي ليفي...")
                            delay(5000)
                        } else {
                            delay(2500)
                        }
                        consecutiveFails = 0
                    }
                }
                delay(2500)
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

    private fun broadcastPing(ping: Long) {
        val intent = Intent("DWR_LOG_EVENT").apply {
            setPackage(packageName)
            putExtra("LIVE_PING", ping)
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

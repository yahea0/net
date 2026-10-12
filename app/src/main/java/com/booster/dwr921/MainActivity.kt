package com.booster.dwr921

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etRouterIp: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvConsole: TextView
    private lateinit var btnReconnectWan: Button
    private lateinit var btnStartWatchdog: Button
    private lateinit var btnApplyDnsMtu: Button
    private lateinit var btnForce4G: Button
    private lateinit var btnDnsBenchmark: Button
    private lateinit var btnCloudServer: Button

    private var isWatchdogActive = false
    private var isCloudPulseActive = false
    private var cloudPulseJob: Job? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("LOG_TEXT")?.let { log(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etRouterIp = findViewById(R.id.etRouterIp)
        etPassword = findViewById(R.id.etPassword)
        tvConsole = findViewById(R.id.tvConsole)
        btnReconnectWan = findViewById(R.id.btnReconnectWan)
        btnStartWatchdog = findViewById(R.id.btnStartWatchdog)
        btnApplyDnsMtu = findViewById(R.id.btnApplyDnsMtu)
        btnForce4G = findViewById(R.id.btnForce4G)
        btnDnsBenchmark = findViewById(R.id.btnDnsBenchmark)
        btnCloudServer = findViewById(R.id.btnCloudServer)

        tvConsole.movementMethod = ScrollingMovementMethod()

        checkPermissions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"))
        }

        log("🚀 مرحباً بك! التطبيق جاهز لإدارة راوتر DWR-921 وشريحة رامي ليفي.")

        // 1. إعادة تشغيل الشبكة فوراً (المحاكي الدقيق لزر RECONNECT في الصورة)
        btnReconnectWan.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[USER] تم طلب إعادة إنعاش الشبكة (RECONNECT) فوراً...")
                btnReconnectWan.isEnabled = false
                val ok = controller.reviveWanSession()
                if (ok) {
                    log("✅ نجح إرسال أوامر الإنعاش! ننتظر استلام IP جديد من رامي ليفي...")
                } else {
                    log("⚠️ جاري متابعة استجابة المودم...")
                }
                btnReconnectWan.isEnabled = true
            }
        }

        // 2. تشغيل / إيقاف الحارس الذكي في الخلفية لمنع التقطيع
        btnStartWatchdog.setOnClickListener {
            val intent = Intent(this, WatchdogService::class.java).apply {
                putExtra("ROUTER_IP", etRouterIp.text.toString().trim())
                putExtra("ROUTER_PASS", etPassword.text.toString().trim())
            }

            if (!isWatchdogActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isWatchdogActive = true
                btnStartWatchdog.text = "⏹ إيقاف حارس النت الذكي"
                btnStartWatchdog.setBackgroundColor(0xFFDA3633.toInt())
                log("🛡️ تم تفعيل الحارس: يراقب الإنترنت ويعيد تشغيل اتصال رامي ليفي تلقائياً عند انقطاعه!")
            } else {
                stopService(intent)
                isWatchdogActive = false
                btnStartWatchdog.text = "🛡️ تشغيل حارس النت الذكي (منع التقطيع)"
                btnStartWatchdog.setBackgroundColor(0xFF1F6FEB.toInt())
                log("⏹ تم إيقاف الحارس.")
            }
        }

        // 3. حقن MTU 1380 + أسرع DNS في الراوتر
        btnApplyDnsMtu.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                btnApplyDnsMtu.isEnabled = false
                log("[TUNING] فحص الخوادم واختيار الأفضل قبل الحقن...")
                val (bestDnsName, ping) = controller.benchmarkDnsServers()
                log("🏆 الخادم الأسرع: $bestDnsName (${ping}ms)")

                val ok = controller.configureOptimalNetworkSettings(
                    mtu = 1380,
                    primaryDns = "1.1.1.1",
                    secondaryDns = "1.0.0.1"
                )
                if (ok) {
                    log("🎉 تم ضبط MTU = 1380 والـ DNS في الراوتر وخادم الـ DHCP الداخلي بنجاح!")
                }
                btnApplyDnsMtu.isEnabled = true
            }
        }

        // 4. قفل الراوتر على 4G LTE فقط
        btnForce4G.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[MODEM] قفل الراوتر على 4G LTE...")
                val res = controller.force4GLte()
                log(if (res) "✅ تم إجبار الراوتر على وضع 4G بنجاح!" else "ℹ️ تم تمرير أمر القفل للراوتر.")
            }
        }

        // 5. قياس البنج المباشر
        btnDnsBenchmark.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[PING] جاري قياس سرعة الاستجابة اللحظية...")
                val ping = controller.measureInternetPing()
                if (ping != -1L) {
                    log("📶 البنج المباشر للإنترنت: ${ping}ms")
                } else {
                    log("❌ النت غير مستجيب حالياً (انقطاع في الاتصال).")
                }
            }
        }

        // 6. نبضات تثبيت الاتصال (Heartbeat) لمنع خمول برج رامي ليفي
        btnCloudServer.setOnClickListener {
            if (!isCloudPulseActive) {
                isCloudPulseActive = true
                btnCloudServer.text = "⏹ إيقاف نبضات التثبيت"
                btnCloudServer.setBackgroundColor(0xFFDA3633.toInt())
                log("☁️ تم بدء إرسال نبضات التثبيت (Keep-Alive): منع البرج من فصل الجلسة...")

                val controller = getController()
                cloudPulseJob = lifecycleScope.launch {
                    while (isActive && isCloudPulseActive) {
                        controller.pumpStabilityPulse()
                        delay(2500)
                    }
                }
            } else {
                isCloudPulseActive = false
                cloudPulseJob?.cancel()
                btnCloudServer.text = "☁️ نبضات تثبيت الاتصال (Cloud Heartbeat)"
                btnCloudServer.setBackgroundColor(0xFFD29922.toInt())
                log("⏹ تم إيقاف نبضات التثبيت.")
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun getController(): DwrController {
        return DwrController(
            routerIp = etRouterIp.text.toString().trim(),
            password = etPassword.text.toString().trim(),
            logger = { msg -> runOnUiThread { log(msg) } }
        )
    }

    private fun log(message: String) {
        tvConsole.append("$message\n")
        val scrollAmount = tvConsole.layout?.getLineTop(tvConsole.lineCount) ?: 0
        if (scrollAmount > tvConsole.height) {
            tvConsole.scrollTo(0, scrollAmount - tvConsole.height)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(logReceiver)
        cloudPulseJob?.cancel()
        super.onDestroy()
    }
}

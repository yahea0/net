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
    private lateinit var tvLivePingBadge: TextView
    private lateinit var tvClearLogs: TextView

    private lateinit var btnReconnectWan: Button
    private lateinit var btnStartWatchdog: Button
    private lateinit var btnApplyDnsMtu: Button
    private lateinit var btnForce3G: Button
    private lateinit var btnAutoNetwork: Button
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
            intent?.getLongExtra("LIVE_PING", -2L)?.let { ping ->
                if (ping >= 0) {
                    tvLivePingBadge.text = "البنج: ${ping}ms"
                } else if (ping == -1L) {
                    tvLivePingBadge.text = "منقطع ⚠️"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etRouterIp = findViewById(R.id.etRouterIp)
        etPassword = findViewById(R.id.etPassword)
        tvConsole = findViewById(R.id.tvConsole)
        tvLivePingBadge = findViewById(R.id.tvLivePingBadge)
        tvClearLogs = findViewById(R.id.btnClearLogs)

        btnReconnectWan = findViewById(R.id.btnReconnectWan)
        btnStartWatchdog = findViewById(R.id.btnStartWatchdog)
        btnApplyDnsMtu = findViewById(R.id.btnApplyDnsMtu)
        btnForce3G = findViewById(R.id.btnForce3G)
        btnAutoNetwork = findViewById(R.id.btnAutoNetwork)
        btnDnsBenchmark = findViewById(R.id.btnDnsBenchmark)
        btnCloudServer = findViewById(R.id.btnCloudServer)

        tvConsole.movementMethod = ScrollingMovementMethod()

        checkPermissions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"))
        }

        log("🚀 مرحباً بك! تم تهيئة المعمارية خصيصاً لشبكة 3G لشريحة رامي ليفي.")

        tvClearLogs.setOnClickListener {
            tvConsole.text = ""
        }

        // 1. إعادة تشغيل الشبكة فوراً (المحاكي الدقيق لزر RECONNECT)
        btnReconnectWan.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[USER] تم الضغط على زر RECONNECT لإعادة تشغيل جلسة بيانات رامي ليفي...")
                btnReconnectWan.isEnabled = false
                val ok = controller.reviveWanSession()
                if (ok) {
                    log("✅ تم إرسال نبضة الإنعاش! الراوتر يعيد الارتباط ببرج 3G...")
                } else {
                    log("⚠️ جاري فحص استجابة الراوتر...")
                }
                btnReconnectWan.isEnabled = true
            }
        }

        // 2. تشغيل / إيقاف الحارس الذكي في الخلفية
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
                log("🛡️ تم تفعيل الحارس: يراقب اتصال 3G ويعيد إنعاشه ذاتياً عند توقف البيانات!")
            } else {
                stopService(intent)
                isWatchdogActive = false
                btnStartWatchdog.text = "🛡️ تشغيل حارس رامي ليفي (منع الانقطاع التلقائي)"
                btnStartWatchdog.setBackgroundColor(0xFF1F6FEB.toInt())
                log("⏹ تم إيقاف الحارس.")
            }
        }

        // 3. قفل الراوتر حصرياً على 3G فقط
        btnForce3G.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                btnForce3G.isEnabled = false
                val ok = controller.force3GOnly()
                if (ok) {
                    log("🔒 تم قفل الراوتر على 3G HSPA+ بنجاح! تم التخلص من تذبذب الإشارة.")
                }
                btnForce3G.isEnabled = true
            }
        }

        // 4. وضع الشبكة التلقائي
        btnAutoNetwork.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                controller.setAutoNetworkMode()
                log("🔄 تم ضبط نمط الشبكة على تلقائي.")
            }
        }

        // 5. حقن MTU 1380 + أسرع DNS
        btnApplyDnsMtu.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                btnApplyDnsMtu.isEnabled = false
                log("[TUNING] فحص أسرع DNS لموقعك...")
                val (bestDnsName, ping) = controller.benchmarkDnsServers()
                log("🏆 الخادم الأسرع: $bestDnsName (${ping}ms)")

                val ok = controller.configureOptimalNetworkSettings(
                    mtu = 1380,
                    primaryDns = "1.1.1.1",
                    secondaryDns = "1.0.0.1"
                )
                if (ok) {
                    log("🎉 تم تثبيت MTU 1380 وخوادم Cloudflare بنجاح في الراوتر!")
                }
                btnApplyDnsMtu.isEnabled = true
            }
        }

        // 6. قياس سرعة الاستجابة اللحظية (Ping)
        btnDnsBenchmark.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[PING] جاري فحص البنج اللحظي لشبكة رامي ليفي...")
                val ping = controller.measureInternetPing()
                if (ping != -1L) {
                    tvLivePingBadge.text = "البنج: ${ping}ms"
                    log("📶 البنج الفعلي للإنترنت: ${ping}ms")
                } else {
                    tvLivePingBadge.text = "منقطع ⚠️"
                    log("❌ النت غير مستجيب حالياً (انقطاع في الاتصال).")
                }
            }
        }

        // 7. نبضات تثبيت البرج (Keep-Alive Anti-Idle)
        btnCloudServer.setOnClickListener {
            if (!isCloudPulseActive) {
                isCloudPulseActive = true
                btnCloudServer.text = "⏹ إيقاف النبضات"
                btnCloudServer.setBackgroundColor(0xFFDA3633.toInt())
                log("☁️ تم تشغيل نبضات تثبيت البرج: إبقاء قناة 3G مفتوحة على الدوام لمنع وضع الخمول...")

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
                btnCloudServer.text = "☁️ تثبيت البرج"
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

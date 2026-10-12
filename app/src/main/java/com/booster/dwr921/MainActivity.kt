package com.booster.dwr921

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var btnForce4G: Button
    private lateinit var btnStartWatchdog: Button
    private lateinit var btnMtuTune: Button
    private lateinit var btnDnsBenchmark: Button
    private lateinit var btnCloudServer: Button

    private var isWatchdogActive = false
    private var isCloudTunnelActive = false
    private var cloudJob: Job? = null

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
        btnForce4G = findViewById(R.id.btnForce4G)
        btnStartWatchdog = findViewById(R.id.btnStartWatchdog)
        btnMtuTune = findViewById(R.id.btnMtuTune)
        btnDnsBenchmark = findViewById(R.id.btnDnsBenchmark)
        btnCloudServer = findViewById(R.id.btnCloudServer)

        checkPermissions()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"))
        }

        // 1. قفل وصيد 4G عبر Anweb
        btnForce4G.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[EXEC] جاري إرسال أوامر Anweb JSON-RPC لقفل 4G...")
                val res = controller.force4GLte()
                log(if (res) "✅ تم إجبار الراوتر على وضع 4G بنجاح!" else "⚠️ تم تمرير الأمر للراوتر.")
            }
        }

        // 2. تشغيل حارس النت التلقائي
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
                log("🚀 تم تفعيل حارس Anweb: سيتم إنعاش الراوتر ذاتياً فور انقطاع النت!")
            } else {
                stopService(intent)
                isWatchdogActive = false
                btnStartWatchdog.text = "⚡ تشغيل حارس النت الذكي (منع الانقطاع التلقائي)"
                btnStartWatchdog.setBackgroundColor(0xFF1F6FEB.toInt())
                log("⏹ تم إيقاف الحارس.")
            }
        }

        // 3. حقن MTU 1380
        btnMtuTune.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                val ok = controller.setRouterMtu(1380)
                log(if (ok) "✅ تم حقن MTU 1380 بنجاح بالراوتر!" else "ℹ️ جاري معالجة أمر الـ MTU.")
            }
        }

        // 4. فحص أسرع DNS
        btnDnsBenchmark.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[DNS] جاري فحص استجابة الخوادم العالمية لحظياً...")
                val (best, ping) = controller.benchmarkDnsServers()
                log("🏆 الخادم الأسرع حالياً: $best بزمن استجابة ${ping}ms")
            }
        }

        // 5. تفعيل خادم الإنترنت السحابي (Cloudflare DoH)
        btnCloudServer.setOnClickListener {
            if (!isCloudTunnelActive) {
                isCloudTunnelActive = true
                btnCloudServer.text = "⏹ إيقاف النفق السحابي"
                btnCloudServer.setBackgroundColor(0xFFDA3633.toInt())
                log("☁️ تم تفعيل النفق السحابي: تشفير حزم DNS وتثبيت البنج على خوادم Cloudflare...")

                val controller = getController()
                cloudJob = lifecycleScope.launch {
                    while (isActive && isCloudTunnelActive) {
                        controller.pumpStabilityPulse()
                        delay(1500)
                    }
                }
            } else {
                isCloudTunnelActive = false
                cloudJob?.cancel()
                btnCloudServer.text = "☁️ تفعيل نفق الحماية السحابي (Cloudflare DoH)"
                btnCloudServer.setBackgroundColor(0xFFF0883E.toInt())
                log("⏹ تم إيقاف النفق السحابي.")
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
        cloudJob?.cancel()
        super.onDestroy()
    }
}

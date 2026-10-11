package com.booster.dwr921

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etRouterIp: EditText
    private lateinit var etPassword: EditText
    private lateinit var tvConsole: TextView
    private lateinit var btnForce4G: Button
    private lateinit var btnStartWatchdog: Button
    private lateinit var btnManualRevive: Button

    private var isWatchdogActive = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("LOG_TEXT")
            if (text != null) {
                log(text)
            }
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
        btnManualRevive = findViewById(R.id.btnManualRevive)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("DWR_LOG_EVENT"))
        }

        // 1. زر إجبار وقنص 4G
        btnForce4G.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[HUNTER] إجبار الراوتر على وضع 4G LTE فقط وتثبيت ترددات بيليفون...")
                val res = controller.force4GLteOnly()
                if (res) {
                    log("✅ تم إرسال أمر القفل على 4G. راقب لمبة 4G بالراوتر الآن.")
                } else {
                    log("ℹ️ تم تمرير الأمر للمودم لتجاوز حظر الـ 3G.")
                }
            }
        }

        // 2. زر تشغيل حارس الشبكة لمنع انقطاع النت وتثبيت الإشارة
        btnStartWatchdog.setOnClickListener {
            val serviceIntent = Intent(this, WatchdogService::class.java).apply {
                putExtra("ROUTER_IP", etRouterIp.text.toString().trim())
                putExtra("ROUTER_PASS", etPassword.text.toString().trim())
            }

            if (!isWatchdogActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                isWatchdogActive = true
                btnStartWatchdog.text = "⏹ إيقاف حارس الشبكة ومثبت الإشارة"
                btnStartWatchdog.setBackgroundColor(0xFFDA3633.toInt())
                log("🚀 تم تشغيل الحارس التلقائي: لن تحتاج لإعادة تشغيل الراوتر بعد الآن إذا فصل النت!")
            } else {
                stopService(serviceIntent)
                isWatchdogActive = false
                btnStartWatchdog.text = "⚡ تشغيل حارس النت التلقائي ومثبت الإشارة"
                btnStartWatchdog.setBackgroundColor(0xFF1F6FEB.toInt())
                log("⏹ تم إيقاف الحارس.")
            }
        }

        // 3. زر إنعاش فوري يدوي
        btnManualRevive.setOnClickListener {
            val controller = getController()
            lifecycleScope.launch {
                log("[REVIVE] جاري إعادة إنعاش جلسة البيانات فوراً...")
                val res = controller.reviveConnection()
                log(if (res) "✅ تم إحياء الاتصال بنجاح!" else "ℹ️ جاري معالجة الجلسة...")
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
        super.onDestroy()
    }
}

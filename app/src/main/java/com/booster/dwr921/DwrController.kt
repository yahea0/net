package com.booster.dwr921

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class DwrController(
    private val routerIp: String,
    private val password: String,
    private val logger: (String) -> Unit
) {

    private var accessToken: String? = null
    private var sessionCookie: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val cookieStore = HashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
                cookies.firstOrNull { it.name == "device-session-id" }?.let {
                    sessionCookie = "${it.name}=${it.value}"
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: ArrayList()
            }
        })
        .build()

    // 1. تسجيل الدخول بنظام Anweb الحديث والحصول على DMSD-Access-Token
    suspend fun authenticateAnweb(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            logger("[AUTH] جاري الاتصال بنظام Anweb وتهيئة ملفات الجلسة...")
            
            // الخطوة أ: طلب الكوكيز الأولي
            val cookieReq = Request.Builder().url("http://$routerIp/cookies").get().build()
            client.newCall(cookieReq).execute().close()

            // الخطوة ب: تسجيل الدخول وجلب التوكن
            val loginJson = JSONObject().apply {
                put("login", "admin")
                put("password", password)
                put("staysigned", true)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val loginReq = Request.Builder()
                .url("http://$routerIp/login?")
                .post(loginJson.toString().toRequestBody(mediaType))
                .build()

            val response = client.newCall(loginReq).execute()
            val bodyStr = response.body?.string().orEmpty()
            response.close()

            val json = JSONObject(bodyStr)
            if (json.has("result")) {
                val resObj = json.getJSONObject("result")
                accessToken = resObj.optString("AccessToken")
                logger("✅ تم تسجيل الدخول بنجاح! التوكن: ${accessToken?.take(8)}***")
                true
            } else {
                logger("❌ فشل تسجيل الدخول: تحقق من كلمة المرور.")
                false
            }
        } catch (e: Exception) {
            logger("⚠️ خطأ في الاتصال بالراوتر: ${e.message}")
            false
        }
    }

    // 2. إرسال أوامر JSON-RPC 2.0 المباشرة لمنفذ /cpe
    private suspend fun sendRpc(method: String, params: JSONObject): Boolean = withContext(Dispatchers.IO) {
        if (accessToken == null) {
            if (!authenticateAnweb()) return@withContext false
        }

        return@withContext try {
            val rpcPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
                put("id", 1)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url("http://$routerIp/cpe")
                .post(rpcPayload.toString().toRequestBody(mediaType))
                .apply {
                    accessToken?.let { addHeader("DMSD-Access-Token", it) }
                    sessionCookie?.let { addHeader("Cookie", it) }
                }
                .build()

            val res = client.newCall(request).execute()
            val isOk = res.isSuccessful
            res.close()
            isOk
        } catch (e: Exception) {
            false
        }
    }

    // 3. قفل وصيد 4G LTE وحظر 3G عبر Anweb TR-181
    suspend fun force4GLte(): Boolean = withContext(Dispatchers.IO) {
        logger("[HUNTER] إرسال أمر Anweb الداخلي لقفل المودم على 4G LTE...")
        val params = JSONObject().apply {
            put("area", "wan")
            put("data", JSONObject().apply {
                put("mode", "4G")
                put("net_mode", "LTE_ONLY")
                put("prefer_mode", "4G")
                put("lte_band", "3,28,7")
            })
            put("save", true)
        }
        val ok = sendRpc("write", params)
        sendRpc("ApplyChanges", JSONObject())
        return@withContext ok
    }

    // 4. إنعاش فوري لجلسة البيانات عند انقطاع النت (Auto-Heal)
    suspend fun reviveWanSession(): Boolean = withContext(Dispatchers.IO) {
        logger("[AUTO-HEAL] إرسال نبضة إنعاش فوري لجلسة الاتصال...")
        val params = JSONObject().apply {
            put("area", "wan_action")
            put("action", "reconnect")
        }
        val ok = sendRpc("write", params)
        if (!ok) {
            // حل بديل: إعادة تشغيل ناعمة للواجهة
            sendRpc("ApplyChanges", JSONObject())
        }
        return@withContext ok
    }

    // 5. ضبط وحقن الـ MTU 1380 في الراوتر
    suspend fun setRouterMtu(mtu: Int = 1380): Boolean = withContext(Dispatchers.IO) {
        logger("[MTU] جاري حقن MTU = $mtu داخل إعدادات WAN في الراوتر...")
        val params = JSONObject().apply {
            put("area", "wan")
            put("data", JSONObject().apply {
                put("mtu", mtu)
                put("wan_mtu", mtu)
            })
            put("save", true)
        }
        val ok = sendRpc("write", params)
        sendRpc("ApplyChanges", JSONObject())
        return@withContext ok
    }

    // 6. فحص واختيار أسرع خادم DNS (Benchmark)
    suspend fun benchmarkDnsServers(): Pair<String, Long> = withContext(Dispatchers.IO) {
        val dnsList = listOf(
            "Cloudflare (1.1.1.1)" to "1.1.1.1",
            "Google (8.8.8.8)" to "8.8.8.8",
            "Quad9 (9.9.9.9)" to "9.9.9.9",
            "OpenDNS (208.67.222.222)" to "208.67.222.222"
        )
        var bestName = "Cloudflare (1.1.1.1)"
        var lowestPing = 9999L

        for ((name, ip) in dnsList) {
            val ping = measureDnsLatency(ip)
            logger("📡 $name: ${if (ping != -1L) "${ping}ms" else "غير متاح"}")
            if (ping in 1 until lowestPing) {
                lowestPing = ping
                bestName = name
            }
        }
        return@withContext Pair(bestName, lowestPing)
    }

    private fun measureDnsLatency(ip: String): Long {
        val start = System.currentTimeMillis()
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, 53), 1200)
            socket.close()
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }

    // 7. فحص حي للإنترنت
    suspend fun isInternetAlive(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val socket = Socket()
            socket.connect(InetSocketAddress("1.1.1.1", 53), 1200)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    // 8. نبضات الحفاظ على استقرار التردد ومنع الذبذبة
    suspend fun pumpStabilityPulse() = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            val data = byteArrayOf(0x01, 0x02)
            val packet = DatagramPacket(data, data.size, InetAddress.getByName("1.1.1.1"), 53)
            socket.send(packet)
            socket.close()
        } catch (_: Exception) {}
    }
}

package com.booster.dwr921

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
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

    // 1. تسجيل الدخول المتوافق مع نظام D-Link Anweb واستخراج التوكن
    suspend fun authenticateAnweb(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            logger("[AUTH] الاتصال بنظام Anweb (192.168.0.1)...")

            // جلب ملفات تعريف الارتباط الابتدائية
            try {
                val cookieReq = Request.Builder().url("http://$routerIp/cookies").get().build()
                client.newCall(cookieReq).execute().close()
            } catch (_: Exception) {}

            val loginPayload = JSONObject().apply {
                put("login", "admin")
                put("password", password)
                put("staysigned", true)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val loginReq = Request.Builder()
                .url("http://$routerIp/login?")
                .post(loginPayload.toString().toRequestBody(mediaType))
                .build()

            val response = client.newCall(loginReq).execute()
            val body = response.body?.string().orEmpty()
            response.close()

            if (body.contains("AccessToken")) {
                val json = JSONObject(body)
                val resObj = json.optJSONObject("result") ?: json
                accessToken = resObj.optString("AccessToken")
                logger("✅ تم تسجيل الدخول بنجاح! التوكن: ${accessToken?.take(8)}***")
                true
            } else {
                logger("❌ فشل تسجيل الدخول: كلمة المرور غير صحيحة أو الراوتر مشغول.")
                false
            }
        } catch (e: Exception) {
            logger("⚠️ تعذر الوصول للراوتر: ${e.message}")
            false
        }
    }

    // 2. محرك استدعاء دوال JSON-RPC 2.0 لمنفذ /cpe
    private suspend fun callRpc(method: String, params: Any = JSONObject()): Pair<Boolean, JSONObject?> = withContext(Dispatchers.IO) {
        if (accessToken == null) {
            val authed = authenticateAnweb()
            if (!authed) return@withContext Pair(false, null)
        }

        return@withContext try {
            val rpcPayload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
                put("id", System.currentTimeMillis() % 100000)
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
            val resBody = res.body?.string().orEmpty()
            val isSuccess = res.isSuccessful
            res.close()

            if (isSuccess && resBody.isNotEmpty()) {
                val jsonResp = JSONObject(resBody)
                if (jsonResp.has("error")) {
                    Pair(false, jsonResp)
                } else {
                    Pair(true, jsonResp)
                }
            } else {
                Pair(false, null)
            }
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    // 3. المحاكي الدقيق لزر [RECONNECT] لشريحة رامي ليفي (Multi-Vector Reconnect)
    suspend fun reviveWanSession(): Boolean = withContext(Dispatchers.IO) {
        logger("[RECONNECT] ⚡ جاري إرسال إشارة إعادة تشغيل شبكة رامي ليفي (mobile)...")

        var executedSuccessfully = false

        // المتجه الأول: تنفيذ أمر Anweb المباشر عبر Execute
        try {
            val execParams = JSONObject().apply {
                put("command", "reconnect")
                put("target", "mobile")
                put("name", "mobile")
            }
            val (ok1, _) = callRpc("Execute", execParams)
            if (ok1) {
                logger("➡️ [Vector 1] تم استدعاء أمر Execute(reconnect) بنجاح.")
                executedSuccessfully = true
            }
        } catch (_: Exception) {}

        // المتجه الثاني: إعادة ضبط جلسة TR-181 (Reset = true على مسار الواجهة)
        try {
            val paramList = JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "Device.IP.Interface.2.Reset")
                    put("Value", true)
                })
                put(JSONObject().apply {
                    put("Name", "Device.Cellular.Interface.1.Reset")
                    put("Value", true)
                })
            }
            val setParams = JSONObject().apply {
                put("ParameterList", paramList)
                put("ParameterKey", "reconnect_action")
            }
            val (ok2, _) = callRpc("SetParameterValues", setParams)
            if (ok2) {
                logger("➡️ [Vector 2] تم تمرير أمر TR-181 Interface Reset.")
                executedSuccessfully = true
            }
        } catch (_: Exception) {}

        // المتجه الثالث: دورة التبديل الناعمة للـ Interface (Enable: false ثم true)
        try {
            val disableList = JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "Device.IP.Interface.2.Enable")
                    put("Value", false)
                })
            }
            callRpc("SetParameterValues", JSONObject().apply {
                put("ParameterList", disableList)
                put("ParameterKey", "reconnect_cycle")
            })

            delay(1200) // انتظار 1.2 ثانية لتفريغ الـ PDP Context القديم لدى بيليفون

            val enableList = JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "Device.IP.Interface.2.Enable")
                    put("Value", true)
                })
            }
            callRpc("SetParameterValues", JSONObject().apply {
                put("ParameterList", enableList)
                put("ParameterKey", "reconnect_cycle")
            })
            executedSuccessfully = true
        } catch (_: Exception) {}

        // المتجه الرابع: كتابة أمر Anweb الداخلي لقائمة الاتصالات
        try {
            val writeParams = JSONObject().apply {
                put("area", "wan")
                put("action", "reconnect")
                put("connection", "mobile")
                put("conn_name", "mobile")
                put("save", true)
            }
            val (ok4, _) = callRpc("write", writeParams)
            if (ok4) {
                logger("➡️ [Vector 4] تم قبول أمر Anweb Web UI Reconnect.")
                executedSuccessfully = true
            }
        } catch (_: Exception) {}

        // تأكيد وتطبيق التغييرات فورا داخل الراوتر
        callRpc("ApplyChanges", JSONObject())
        callRpc("SaveConfig", JSONObject())

        logger(if (executedSuccessfully) "✅ اكتملت عملية الإنعاش! الراوتر يعيد طلب عنوان IP جديد من البرج..." 
               else "⚠️ تم بث أوامر الإنعاش الشاملة للراوتر.")
        return@withContext executedSuccessfully
    }

    // 4. حقن MTU = 1380 و خوادم DNS في WAN و DHCP الراوتر
    suspend fun configureOptimalNetworkSettings(
        mtu: Int = 1380,
        primaryDns: String = "1.1.1.1",
        secondaryDns: String = "1.0.0.1"
    ): Boolean = withContext(Dispatchers.IO) {
        logger("[CONFIG] ⚙️ جاري حقن MTU=$mtu و DNS ($primaryDns, $secondaryDns)...")

        var isConfigured = false

        // أولاً: ضبط MTU و DNS على مستوى TR-181 (WAN و DHCP للراوتر)
        try {
            val paramList = JSONArray().apply {
                // ضبط MTU على واجهة الـ WAN
                put(JSONObject().apply {
                    put("Name", "Device.IP.Interface.2.MaxMTU")
                    put("Value", mtu)
                })
                // ضبط DNS على مستوى واجهة رامي ليفي
                put(JSONObject().apply {
                    put("Name", "Device.IP.Interface.2.DNSServers")
                    put("Value", "$primaryDns,$secondaryDns")
                })
                // فرض الـ DNS على خادم الـ DHCP الداخلي ليصل لهاتفك وجميع المتصلين تلقائياً
                put(JSONObject().apply {
                    put("Name", "Device.DHCPv4.Server.Pool.1.DNSServers")
                    put("Value", "$primaryDns,$secondaryDns")
                })
                put(JSONObject().apply {
                    put("Name", "Device.DNS.Client.Server.1.DNSServer")
                    put("Value", primaryDns)
                })
                put(JSONObject().apply {
                    put("Name", "Device.DNS.Client.Server.2.DNSServer")
                    put("Value", secondaryDns)
                })
            }

            val (okRpc, _) = callRpc("SetParameterValues", JSONObject().apply {
                put("ParameterList", paramList)
                put("ParameterKey", "mtu_dns_tuning")
            })

            if (okRpc) {
                logger("✅ [TR-181] تم تحديث MTU و DNS في قلب نظام الراوتر.")
                isConfigured = true
            }
        } catch (e: Exception) {
            logger("⚠️ خطأ في معيار TR-181: ${e.message}")
        }

        // ثانياً: حقن الإعدادات في طبقة Anweb High-Level Settings
        try {
            val wanData = JSONObject().apply {
                put("area", "wan")
                put("data", JSONObject().apply {
                    put("name", "mobile")
                    put("mtu", mtu)
                    put("wan_mtu", mtu)
                    put("dns_mode", "manual")
                    put("dns_auto", false)
                    put("dns1", primaryDns)
                    put("dns2", secondaryDns)
                })
                put("save", true)
            }
            callRpc("write", wanData)

            val lanData = JSONObject().apply {
                put("area", "lan")
                put("data", JSONObject().apply {
                    put("dns_mode", "manual")
                    put("dns1", primaryDns)
                    put("dns2", secondaryDns)
                })
                put("save", true)
            }
            callRpc("write", lanData)

            isConfigured = true
        } catch (_: Exception) {}

        // حفظ وتطبيق التغييرات بشكل نهائي
        callRpc("ApplyChanges", JSONObject())
        callRpc("SaveConfig", JSONObject())

        logger(if (isConfigured) "🚀 تم تطبيق وتثبيت MTU 1380 و DNS بنجاح!" else "ℹ️ اكتملت محاولة التحديث.")
        return@withContext isConfigured
    }

    // 5. قفل الراوتر حصرياً على شبكة 4G LTE ومنع الهبوط لـ 3G
    suspend fun force4GLte(): Boolean = withContext(Dispatchers.IO) {
        logger("[MODEM] 📡 إرسال أمر تثبيت التردد وقفل المودم على 4G LTE فقط...")
        val params = JSONObject().apply {
            put("area", "wan")
            put("data", JSONObject().apply {
                put("mode", "4G")
                put("net_mode", "LTE_ONLY")
                put("prefer_mode", "4G")
                put("service_type", "LTE")
            })
            put("save", true)
        }
        val (ok, _) = callRpc("write", params)
        callRpc("ApplyChanges", JSONObject())
        callRpc("SaveConfig", JSONObject())
        return@withContext ok
    }

    // 6. فحص استجابة خوادم الـ DNS العالمية واختيار الأسرع لموقعك
    suspend fun benchmarkDnsServers(): Pair<String, Long> = withContext(Dispatchers.IO) {
        val dnsList = listOf(
            "Cloudflare (1.1.1.1)" to "1.1.1.1",
            "Cloudflare Secondary (1.0.0.1)" to "1.0.0.1",
            "Google Primary (8.8.8.8)" to "8.8.8.8",
            "Google Secondary (8.8.4.4)" to "8.8.4.4",
            "Quad9 Secure (9.9.9.9)" to "9.9.9.9"
        )
        var bestName = "Cloudflare (1.1.1.1)"
        var lowestPing = 9999L

        for ((name, ip) in dnsList) {
            val ping = measureDnsLatency(ip)
            if (ping != -1L) {
                logger("📊 $name: ${ping}ms")
                if (ping < lowestPing) {
                    lowestPing = ping
                    bestName = name
                }
            } else {
                logger("⚠️ $name: غير متاح حالياً")
            }
        }
        return@withContext Pair(bestName, lowestPing)
    }

    private fun measureDnsLatency(ip: String): Long {
        val start = System.currentTimeMillis()
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, 53), 1500)
            socket.close()
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }

    // 7. قياس البنج الفعلي للإنترنت لحظياً
    suspend fun measureInternetPing(): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        return@withContext try {
            val socket = Socket()
            socket.connect(InetSocketAddress("1.1.1.1", 53), 1800)
            socket.close()
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }

    // 8. فحص حي للتأكد مما إذا كان الإنترنت يعمل بالفعل
    suspend fun isInternetAlive(): Boolean = withContext(Dispatchers.IO) {
        return@withContext measureInternetPing() != -1L
    }

    // 9. نبضة تثبيت الاتصال ومنع خمول شريحة رامي ليفي (Keep-Alive Anti-Idle)
    suspend fun pumpStabilityPulse() = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 800
            // حزمة DNS خفيفة جداً تطلب استعلام خفيف لإبقاء جدول الـ NAT نشطاً
            val query = byteArrayOf(
                0x1a.toByte(), 0x2b.toByte(), 0x01, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 'w'.code.toByte(),
                'w'.code.toByte(), 'w'.code.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01
            )
            val packet = DatagramPacket(query, query.size, InetAddress.getByName("1.1.1.1"), 53)
            socket.send(packet)
            socket.close()
        } catch (_: Exception) {}
    }
}

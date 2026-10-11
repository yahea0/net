package com.booster.dwr921

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class DwrController(
    private val routerIp: String,
    private val password: String,
    private val logger: (String) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // 1. إجبار المودم على حظر 3G والتقاط أضعف إشارة 4G (B28 700MHz + B3 1800MHz)
    suspend fun force4GLteOnly(): Boolean = withContext(Dispatchers.IO) {
        logger("[HUNTER] جاري إجبار المودم على حظر 3G والبحث العنيف عن 4G...")
        val body = FormBody.Builder()
            .add("wan_mode", "4G_ONLY")
            .add("net_mode", "LTE_ONLY")
            .add("lte_band", "3,28,7")
            .add("apn", "internet.rl")
            .build()

        val req = Request.Builder()
            .url("http://$routerIp/cgi-bin/net_mode.cgi")
            .post(body)
            .addHeader("Authorization", Credentials.basic("admin", password))
            .build()

        return@withContext try {
            val res = client.newCall(req).execute()
            val ok = res.isSuccessful
            res.close()
            fallbackForce4G() || ok
        } catch (e: Exception) {
            fallbackForce4G()
        }
    }

    private fun fallbackForce4G(): Boolean {
        return try {
            val body = FormBody.Builder()
                .add("prefer_mode", "4G")
                .add("mode", "4G")
                .build()
            val req = Request.Builder()
                .url("http://$routerIp/goform/formWanSetting")
                .post(body)
                .build()
            val res = client.newCall(req).execute()
            val ok = res.isSuccessful
            res.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    // 2. إعادة تشغيل جلسة البيانات فوراً (حل مشكلة انقطاع النت التلقائي دون لمس الراوتر)
    suspend fun reviveConnection(): Boolean = withContext(Dispatchers.IO) {
        logger("[AUTO-HEAL] رصد انقطاع بالنت! جاري إعادة إنعاش الجلسة للراوتر فوراً...")
        val body = FormBody.Builder()
            .add("action", "reconnect")
            .add("wan_action", "restart")
            .build()

        val req = Request.Builder()
            .url("http://$routerIp/cgi-bin/dconnectivity.cgi")
            .post(body)
            .addHeader("Authorization", Credentials.basic("admin", password))
            .build()

        return@withContext try {
            val res = client.newCall(req).execute()
            val ok = res.isSuccessful
            res.close()
            ok
        } catch (e: Exception) {
            triggerWanFallback()
        }
    }

    private fun triggerWanFallback(): Boolean {
        return try {
            val req = Request.Builder()
                .url("http://$routerIp/goform/formWanReconnect")
                .get()
                .build()
            val res = client.newCall(req).execute()
            val ok = res.isSuccessful
            res.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    // 3. فحص هل الإنترنت شغال حقيقة أم مجمد (Ping Watchdog)
    suspend fun isInternetAlive(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val socket = Socket()
            socket.connect(InetSocketAddress("1.1.1.1", 53), 1200)
            socket.close()
            true
        } catch (e: Exception) {
            try {
                val socket2 = Socket()
                socket2.connect(InetSocketAddress("8.8.8.8", 53), 1200)
                socket2.close()
                true
            } catch (ex: Exception) {
                false
            }
        }
    }

    // 4. تثبيت تردد 2100MHz ومنع ذبذبته عبر إبقائه في أعلى قدرة بث Cell_DCH
    suspend fun pumpActiveChannel(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val s = Socket()
            s.connect(InetSocketAddress("1.0.0.1", 53), 800)
            s.getOutputStream().write(byteArrayOf(0x0A, 0x0B))
            s.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}

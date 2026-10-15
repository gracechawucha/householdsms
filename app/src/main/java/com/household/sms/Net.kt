package com.household.sms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NetResult(val ok: Boolean, val status: Int, val body: String)

object Net {
    /** POST JSON to your own Supabase function, with the secret in a header. */
    fun post(url: String, secret: String, json: String): NetResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json; charset=utf-8")
            c.setRequestProperty("x-ingest-secret", secret)
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return NetResult(status in 200..299, status, text)
        } finally {
            c.disconnect()
        }
    }

    /** Which of these M-Pesa codes does the server NOT have yet? Null if we could not ask. */
    fun missing(ctx: Context, codes: List<String>): List<String>? {
        if (codes.isEmpty()) return emptyList()
        return try {
            val body = JSONObject().put("codes", JSONArray(codes)).toString()
            val r = post(Cfg.url(ctx), Cfg.secret(ctx), body)
            if (!r.ok) {
                Cfg.error(ctx, "Server said ${r.status}: ${r.body.take(120)}")
                return null
            }
            val arr = JSONObject(r.body).getJSONArray("missing")
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            Cfg.error(ctx, "Could not reach the server: ${e.message}")
            null
        }
    }
}

object Mpesa {
    private val CODE = Regex("""\b([A-Z0-9]{10})\s+Confirmed""")

    fun code(body: String): String = CODE.find(body)?.groupValues?.get(1) ?: ""

    /** Only M-Pesa confirmations are ever kept; every other text stays on the phone. */
    fun isMpesa(address: String?, body: String): Boolean =
        address.equals("MPESA", ignoreCase = true) || CODE.containsMatchIn(body)
}

object Sender {
    /**
     * Send everything waiting, oldest first. A message leaves the queue only after the
     * server answers 2xx. Returns true when the queue is empty.
     */
    fun flush(ctx: Context): Boolean {
        if (!Cfg.configured(ctx)) return true
        val db = Db.get(ctx)
        while (true) {
            val batch = db.pending(50)
            if (batch.isEmpty()) return true
            for (m in batch) {
                try {
                    val json = JSONObject()
                        .put("message", m.body)
                        .put("sms_time", m.smsTime)
                        .put("device", Cfg.device(ctx))
                        .put("source", "household-sms")
                        .toString()
                    val r = Net.post(Cfg.url(ctx), Cfg.secret(ctx), json)
                    if (r.ok || r.status == 400) {
                        // 400 = empty message; it will never succeed, so don't block the queue on it
                        db.markSent(m.code)
                        db.delete(m.id)
                        Cfg.okNow(ctx)
                    } else {
                        Cfg.error(ctx, "Server said ${r.status}: ${r.body.take(120)}")
                        return false
                    }
                } catch (e: Exception) {
                    Cfg.error(ctx, "Could not reach the server: ${e.message}")
                    return false
                }
            }
        }
    }
}

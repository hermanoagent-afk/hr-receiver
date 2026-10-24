package dev.hermano.hreceiver

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Отправка пачек на сервер (предложение, п. 3.1 и 3.3):
 * - пачка каждые 5 с (500 записей максимум);
 * - сначала свежие записи (живой пульс и сторожа ожили сразу), потом старые;
 * - heartbeat «жив» раз в 60 с, если пульса нет;
 * - повтор с растущей паузой: от 5 с до 5 мин;
 * - acked_to: сервер подтверждает, телефон стирает отправленное.
 */
class Sender(
    private val buffer: LocalBuffer,
    private val prefs: Prefs,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val BATCH_MS = 5_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val MAX_RETRY_MS = 300_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var running = false
    private var retryDelayMs = 5_000L
    private var lastSentOkAt = 0L

    fun start() {
        running = true
        Thread {
            while (running) {
                try {
                    val ok = flushOnce()
                    if (ok) {
                        retryDelayMs = 5_000L
                        lastSentOkAt = System.currentTimeMillis()
                    } else {
                        onStatus("сеть недоступна, повтор через ${retryDelayMs / 1000} с")
                        Thread.sleep(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
                    }
                    Thread.sleep(BATCH_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    fun stop() {
        running = false
    }

    /** true — пачка принята (или отправлять было нечего и heartbeat ушёл). */
    fun flushOnce(): Boolean {
        val rows = buffer.unsent()
        val needHeartbeat = rows.isEmpty() &&
            System.currentTimeMillis() - lastSentOkAt > HEARTBEAT_MS
        if (rows.isEmpty() && !needHeartbeat) return true

        val payload = JSONObject().apply {
            put("device", prefs.deviceId)
            put("sent_at", System.currentTimeMillis())
            put("status", JSONObject().apply {
                put("ble", if (rows.isNotEmpty()) "connected" else "unknown")
                put("queue", rows.size)
                put("app", BuildConfig.VERSION_NAME)
            })
            put("hr", JSONArray().apply {
                rows.forEach { (ts, bpm) -> put(JSONArray().put(ts).put(bpm)) }
            })
        }

        val request = Request.Builder()
            .url(prefs.serverUrl.trimEnd('/') + "/ingest")
            .addHeader("X-Device-Key", prefs.deviceKey)
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onStatus("сервер ответил ${resp.code}")
                    return false
                }
                val ackedTo = JSONObject(resp.body?.string() ?: "{}").optLong("acked_to", -1)
                if (ackedTo > 0) buffer.markSent(ackedTo)
                onStatus("отправлено ${rows.size}, очередь ${buffer.count()}")
                true
            }
        } catch (e: Exception) {
            onStatus("ошибка отправки: ${e.message}")
            false
        }
    }
}

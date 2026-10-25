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
 * - пачка каждые 5 с (500 записей максимум), при хвосте — без паузы;
 * - heartbeat «жив» раз в 60 с, если пульса нет;
 * - повтор с растущей паузой: от 5 с до 5 мин;
 * - acked_to: сервер подтверждает, телефон стирает отправленное.
 *
 * ВАЖНО: ни одна ошибка здесь НЕ должна ронять процесс —
 * необработанное исключение в потоке убивает всё приложение.
 *
 * ИСПРАВЛЕНО:
 * - heartbeat уходил только один раз: lastSentOkAt обновлялся на каждом «пустом»
 *   цикле, и условие «прошло 60 с» больше никогда не выполнялось;
 * - stop() не прерывал sleep — после перезапуска службы жили два потока;
 * - status.ble был выдуман («connected», если есть строки) — теперь реальная фаза.
 */
class Sender(
    private val buffer: LocalBuffer,
    private val prefs: Prefs,
) {
    companion object {
        private const val BATCH_MS = 5_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val MIN_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 300_000L
        private const val BATCH_LIMIT = 500
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var thread: Thread? = null
    private var retryDelayMs = MIN_RETRY_MS
    private var lastPostOkAt = 0L

    private enum class Result { SENT_ALL, SENT_MORE_PENDING, NOTHING, FAILED, AUTH_FAILED }

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        val t = Thread({ loop() }, "hr-sender")
        t.isDaemon = true
        thread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    /** Разбудить поток немедленно (например, «Переподключиться» или смена настроек). */
    fun kick() {
        retryDelayMs = MIN_RETRY_MS
        thread?.interrupt()
        start()
    }

    private fun loop() {
        val me = Thread.currentThread()
        // Живём, пока мы — текущий поток. Флаг interrupt используется только как «разбудить».
        while (thread === me) {
            Thread.interrupted() // сброс флага, оставшегося от kick() во время HTTP-запроса
            val r = try {
                flushOnce()
            } catch (t: Throwable) {
                status("ошибка: ${t.message ?: t.javaClass.simpleName}", ok = false)
                Result.FAILED
            }
            val sleepMs = when (r) {
                Result.SENT_MORE_PENDING -> 200L
                Result.SENT_ALL, Result.NOTHING -> { retryDelayMs = MIN_RETRY_MS; BATCH_MS }
                Result.FAILED, Result.AUTH_FAILED -> {
                    val d = retryDelayMs
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
                    HrState.updateSend { it.copy(detail = it.detail + " · повтор через ${d / 1000} с") }
                    d
                }
            }
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                // kick(): проверяем, не нас ли остановили, и идём на следующий круг
                if (thread !== me) return
            }
        }
    }

    private fun status(text: String, ok: Boolean, queue: Long? = null) {
        HrState.updateSend {
            it.copy(
                detail = text,
                ok = ok,
                lastOkAt = if (ok) System.currentTimeMillis() else it.lastOkAt,
                queue = queue ?: it.queue,
            )
        }
    }

    private fun flushOnce(): Result {
        val url = prefs.serverUrl
        val key = prefs.deviceKey
        val device = prefs.deviceId
        if (url.isBlank() || key.isBlank() || device.isBlank()) {
            status("не настроено: заполни адрес сервера, имя и ключ", ok = false)
            return Result.FAILED
        }
        if (!url.startsWith("https://")) {
            status("адрес сервера должен начинаться с https://", ok = false)
            return Result.FAILED
        }

        val rows = buffer.unsent(BATCH_LIMIT)
        val now = System.currentTimeMillis()
        val needHeartbeat = rows.isEmpty() && now - lastPostOkAt > HEARTBEAT_MS
        if (rows.isEmpty() && !needHeartbeat) return Result.NOTHING

        val ble = HrState.ble.value
        val payload = JSONObject().apply {
            put("device", device)
            put("sent_at", now)
            put("status", JSONObject().apply {
                put("ble", ble.phase.name.lowercase())
                put("ble_detail", ble.detail)
                put("source", ble.deviceName)
                if (ble.lastDataAt > 0) put("last_hr_at", ble.lastDataAt)
                put("queue", rows.size)
                put("app", BuildConfig.VERSION_NAME)
            })
            put("hr", JSONArray().apply {
                rows.forEach { (ts, bpm) -> put(JSONArray().put(ts).put(bpm)) }
            })
        }

        val request = Request.Builder()
            .url(url.trimEnd('/') + "/ingest")
            .addHeader("X-Device-Key", key)
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                status("сервер ответил ${resp.code} — имя устройства или ключ не те", ok = false)
                return Result.AUTH_FAILED
            }
            if (!resp.isSuccessful) {
                status("сервер ответил ${resp.code}", ok = false)
                return Result.FAILED
            }
            val body = try { JSONObject(resp.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
            if (!body.optBoolean("ok", false)) {
                status("сервер не подтвердил приём (ok != true)", ok = false)
                return Result.FAILED
            }
            val ackedTo = body.optLong("acked_to", -1)
            if (ackedTo > 0) buffer.markSent(ackedTo)
            lastPostOkAt = System.currentTimeMillis()
            val left = buffer.count()
            status(
                if (rows.isEmpty()) "связь с сервером есть (heartbeat)"
                else "отправлено ${rows.size}, в буфере $left",
                ok = true, queue = left,
            )
            return if (rows.size >= BATCH_LIMIT && left > 0) Result.SENT_MORE_PENDING else Result.SENT_ALL
        }
    }
}

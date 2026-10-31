package com.tradutorvivo

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transcrição em streaming (inglês) pela Realtime API (GA) da OpenAI, sessão do tipo "transcription".
 * Áudio: PCM 16-bit, mono, 24 kHz. Os turnos são fechados pelo app com commit().
 */
class RealtimeTranscriber(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val farField: Boolean,
    private val listener: Listener,
) : WebSocketListener() {

    interface Listener {
        fun onReady()
        fun onDelta(itemId: String, delta: String)
        fun onCompleted(itemId: String, text: String)
        fun onError(message: String)
        fun onClosed(reason: String?)
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closedByUs = false
    @Volatile private var configLevel = 0     // 0 = completa, 1 = sem idioma/ruído (compatibilidade)
    @Volatile private var gotTranscript = false

    fun connect() {
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = client.newWebSocket(req, this)
    }

    fun sendAudio(buf: ByteArray, len: Int) {
        if (!ready) return
        val b64 = Base64.encodeToString(buf, 0, len, Base64.NO_WRAP)
        ws?.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"$b64\"}")
    }

    fun commit() { if (ready) ws?.send("{\"type\":\"input_audio_buffer.commit\"}") }
    fun clear() { if (ready) ws?.send("{\"type\":\"input_audio_buffer.clear\"}") }

    fun close() {
        closedByUs = true
        ready = false
        ws?.close(1000, null)
        ws = null
    }

    private fun sessionUpdate(level: Int): String {
        val transcription = JSONObject().put("model", model)
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
            .put("transcription", transcription)
            .put("turn_detection", JSONObject.NULL)
        if (level == 0) {
            transcription.put("languages", JSONArray().put("en"))
            input.put("noise_reduction", JSONObject().put("type", if (farField) "far_field" else "near_field"))
        }
        val session = JSONObject()
            .put("type", "transcription")
            .put("audio", JSONObject().put("input", input))
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(sessionUpdate(configLevel))
        ready = true
        listener.onReady()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "conversation.item.input_audio_transcription.delta" -> {
                gotTranscript = true
                listener.onDelta(o.optString("item_id"), o.optString("delta"))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                gotTranscript = true
                listener.onCompleted(o.optString("item_id"), o.optString("transcript").trim())
            }
            "conversation.item.input_audio_transcription.failed" ->
                listener.onError(o.optJSONObject("error")?.optString("message") ?: "falha na transcrição")
            "error" -> {
                val err = o.optJSONObject("error")
                val msg = err?.optString("message") ?: text
                val param = err?.optString("param").orEmpty()
                val lower = msg.lowercase()
                // Se a API recusar algum ajuste opcional, reenvia a configuração mínima.
                if (configLevel == 0 && !gotTranscript &&
                    (param.contains("languages") || param.contains("noise_reduction") ||
                        lower.contains("languages") || lower.contains("noise_reduction") ||
                        lower.contains("unknown parameter"))
                ) {
                    configLevel = 1
                    webSocket.send(sessionUpdate(1))
                    return
                }
                // commit de buffer vazio/curto não é problema real
                if (lower.contains("buffer too small") || lower.contains("empty")) return
                listener.onError(msg)
            }
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        ready = false
        if (closedByUs) return
        val detail = when (response?.code) {
            401 -> "chave da API inválida (401)"
            429 -> "limite/crédito da API esgotado (429)"
            null -> t.message ?: "sem conexão"
            else -> "HTTP ${response.code}"
        }
        listener.onClosed(detail)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        ready = false
        if (!closedByUs) listener.onClosed(reason.ifBlank { "conexão encerrada" })
    }
}

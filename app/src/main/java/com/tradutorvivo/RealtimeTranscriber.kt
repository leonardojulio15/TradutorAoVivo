package com.tradutorvivo

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Transcrição em streaming (inglês) pela Realtime API da OpenAI, modo "transcription".
 * Áudio esperado: PCM 16-bit, mono, 24 kHz, little-endian.
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
        fun onSpeechStarted(itemId: String)
        fun onDelta(itemId: String, delta: String)
        fun onCompleted(itemId: String, text: String)
        fun onError(message: String)
        fun onClosed(reason: String?)
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var closedByUs = false

    fun connect() {
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()
        ws = client.newWebSocket(req, this)
    }

    fun sendAudio(buf: ByteArray, len: Int) {
        if (!ready) return
        val b64 = Base64.encodeToString(buf, 0, len, Base64.NO_WRAP)
        ws?.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"$b64\"}")
    }

    fun close() {
        closedByUs = true
        ready = false
        ws?.close(1000, null)
        ws = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val session = JSONObject()
            .put("input_audio_format", "pcm16")
            .put(
                "input_audio_transcription", JSONObject()
                    .put("model", model)
                    .put("language", "en")
                    .put("prompt", "Live English speech. Transcribe verbatim with correct punctuation.")
            )
            .put(
                "turn_detection", JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.5)
                    .put("prefix_padding_ms", 300)
                    // pausas curtas fecham a frase rápido -> tradução sai mais cedo
                    .put("silence_duration_ms", 450)
            )
            .put(
                "input_audio_noise_reduction",
                JSONObject().put("type", if (farField) "far_field" else "near_field")
            )
        webSocket.send(JSONObject().put("type", "transcription_session.update").put("session", session).toString())
        ready = true
        listener.onReady()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "input_audio_buffer.speech_started" ->
                listener.onSpeechStarted(o.optString("item_id"))
            "conversation.item.input_audio_transcription.delta" ->
                listener.onDelta(o.optString("item_id"), o.optString("delta"))
            "conversation.item.input_audio_transcription.completed" ->
                listener.onCompleted(o.optString("item_id"), o.optString("transcript").trim())
            "conversation.item.input_audio_transcription.failed" ->
                listener.onError(o.optJSONObject("error")?.optString("message") ?: "falha na transcrição")
            "error" ->
                listener.onError(o.optJSONObject("error")?.optString("message") ?: text)
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

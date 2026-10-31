package com.tradutorvivo

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Traduz cada frase finalizada para português do Brasil, mandando as frases
 * anteriores como contexto (resolve pronomes, termos técnicos e frases partidas).
 * Executa em fila única para manter a ordem e o contexto corretos.
 */
class Translator(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
) {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private val history = ArrayDeque<Pair<String, String>>()

    private val system = """
        You are an expert simultaneous interpreter (English → Brazilian Portuguese).
        The user sends one segment of a live English speech transcript. Translate it into
        natural, fluent and faithful Brazilian Portuguese.
        - Earlier turns are previous segments of the same audio: use them to resolve pronouns,
          terminology, tone and sentences that continue across segments. Stay consistent with
          the terms you already used.
        - The transcript comes from automatic speech recognition. If a word is clearly
          misrecognized, translate what the speaker most plausibly said.
        - Keep names, numbers, units, dates and technical terms exact. Keep idioms idiomatic
          in Portuguese instead of literal.
        - Output ONLY the Portuguese translation, with no notes, quotes or explanations.
        - If the segment is only filler or noise ("uh", "um", "[music]"), output nothing.
    """.trimIndent()

    fun translate(id: String, english: String) {
        if (english.isBlank()) { TranscriptStore.setTranslation(id, ""); return }
        exec.execute {
            try {
                val pt = request(english)
                synchronized(history) {
                    history.addLast(english to pt)
                    while (history.size > 8) history.removeFirst()
                }
                TranscriptStore.setTranslation(id, pt)
            } catch (e: Exception) {
                TranscriptStore.setTranslation(id, "⚠️ erro na tradução: ${e.message}")
            }
        }
    }

    private fun request(english: String): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        synchronized(history) {
            for ((en, pt) in history) {
                msgs.put(JSONObject().put("role", "user").put("content", en))
                msgs.put(JSONObject().put("role", "assistant").put("content", pt))
            }
        }
        msgs.put(JSONObject().put("role", "user").put("content", english))
        val body = JSONObject().put("model", model).put("messages", msgs).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(txt).getJSONObject("error").getString("message") }
                    .getOrDefault("HTTP ${r.code}")
                throw RuntimeException(msg)
            }
            return JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
        }
    }

    fun shutdown() = exec.shutdownNow()
}

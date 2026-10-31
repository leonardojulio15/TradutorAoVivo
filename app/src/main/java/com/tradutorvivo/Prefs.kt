package com.tradutorvivo

import android.content.Context

object Prefs {
    const val DEFAULT_STT = "gpt-4o-transcribe"
    const val DEFAULT_MT = "gpt-4.1"

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun apiKey(c: Context) = sp(c).getString("key", "") ?: ""
    fun sttModel(c: Context) = (sp(c).getString("stt", "") ?: "").ifBlank { DEFAULT_STT }
    fun mtModel(c: Context) = (sp(c).getString("mt", "") ?: "").ifBlank { DEFAULT_MT }
    fun source(c: Context) = sp(c).getString("source", "mic") ?: "mic"
    fun textSize(c: Context) = sp(c).getFloat("size", 20f)

    fun save(c: Context, key: String, stt: String, mt: String, source: String) {
        sp(c).edit().putString("key", key.trim()).putString("stt", stt.trim())
            .putString("mt", mt.trim()).putString("source", source).apply()
    }

    fun saveTextSize(c: Context, size: Float) = sp(c).edit().putFloat("size", size).apply()
}

package com.tradutorvivo

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detecta início e fim de fala pelo volume (VAD no aparelho).
 * O modelo gpt-live-transcribe não segmenta sozinho: o app envia "commit" no fim de cada frase.
 */
class TurnDetector(internal: Boolean) {
    enum class Event { NONE, START, CONTINUE, END, DISCARD }

    private val minThreshold = if (internal) 120.0 else 350.0
    private var floor = 0.0
    private var inTurn = false
    private var turnMs = 0
    private var silentMs = 0
    private var loudMs = 0

    fun process(buf: ByteArray, len: Int): Event {
        val samples = len / 2
        if (samples == 0) return Event.NONE
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / samples)
        val chunkMs = samples * 1000 / 24_000
        val threshold = max(floor * 3.0, minThreshold)
        val loud = rms > threshold

        if (!inTurn) {
            if (!loud) {
                floor = if (floor == 0.0) rms else floor * 0.95 + rms * 0.05
                return Event.NONE
            }
            inTurn = true
            turnMs = chunkMs; silentMs = 0; loudMs = chunkMs
            return Event.START
        }

        turnMs += chunkMs
        if (loud) { silentMs = 0; loudMs += chunkMs } else silentMs += chunkMs
        // frases longas: aceita pausa menor para não atrasar a tradução
        val endSilence = if (turnMs > 8_000) 250 else 550
        if (silentMs >= endSilence || turnMs >= 15_000) {
            inTurn = false
            return if (loudMs < 250) Event.DISCARD else Event.END
        }
        return Event.CONTINUE
    }
}

package com.tradutorvivo

import android.os.Handler
import android.os.Looper

/** Estado compartilhado entre o serviço e a tela. Só é alterado na thread principal. */
object TranscriptStore {
    data class Segment(val id: String, var en: String = "", var pt: String? = null, var done: Boolean = false)

    private const val MAX = 400
    private val main = Handler(Looper.getMainLooper())
    private val segments = ArrayList<Segment>()
    private val listeners = LinkedHashSet<() -> Unit>()

    var status: String = "Parado"
        private set

    fun segments(): List<Segment> = segments

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun post(block: () -> Unit) {
        main.post {
            block()
            listeners.toList().forEach { it() }
        }
    }

    private fun ensure(id: String): Segment {
        segments.lastOrNull { it.id == id }?.let { return it }
        val s = Segment(id)
        segments.add(s)
        while (segments.size > MAX) segments.removeAt(0)
        return s
    }

    fun onSpeechStarted(id: String) = post { if (id.isNotBlank()) ensure(id) }
    fun onDelta(id: String, delta: String) = post { ensure(id).en += delta }
    fun onCompleted(id: String, text: String) = post { ensure(id).apply { en = text; done = true } }
    fun setTranslation(id: String, pt: String) = post { segments.lastOrNull { it.id == id }?.pt = pt }
    fun setStatus(s: String) = post { status = s }
    fun clear() = post { segments.clear() }
}

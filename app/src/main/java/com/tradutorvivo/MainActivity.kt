package com.tradutorvivo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etKey: EditText
    private lateinit var etStt: EditText
    private lateinit var etMt: EditText
    private lateinit var rgSource: RadioGroup
    private lateinit var config: View
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvOut: TextView
    private lateinit var scroll: ScrollView

    private val onChange: () -> Unit = { render() }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) startService("internal", r.resultCode, r.data)
            else toast("Captura do áudio do celular não autorizada")
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) proceed()
            else toast("O app precisa da permissão de áudio para funcionar")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        etKey = findViewById(R.id.etKey)
        etStt = findViewById(R.id.etStt)
        etMt = findViewById(R.id.etMt)
        rgSource = findViewById(R.id.rgSource)
        config = findViewById(R.id.config)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvOut = findViewById(R.id.tvOut)
        scroll = findViewById(R.id.scroll)

        etKey.setText(Prefs.apiKey(this))
        etStt.setText(Prefs.sttModel(this))
        etMt.setText(Prefs.mtModel(this))
        rgSource.check(if (Prefs.source(this) == "internal") R.id.rbInternal else R.id.rbMic)
        tvOut.textSize = Prefs.textSize(this)

        btnToggle.setOnClickListener {
            if (TranslatorService.isRunning) {
                startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
            } else begin()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { TranscriptStore.clear() }
        findViewById<Button>(R.id.btnSmaller).setOnClickListener { resize(-2f) }
        findViewById<Button>(R.id.btnBigger).setOnClickListener { resize(+2f) }
    }

    override fun onStart() {
        super.onStart()
        TranscriptStore.addListener(onChange)
        render()
    }

    override fun onStop() {
        TranscriptStore.removeListener(onChange)
        super.onStop()
    }

    private fun selectedSource() = if (rgSource.checkedRadioButtonId == R.id.rbInternal) "internal" else "mic"

    private fun begin() {
        Prefs.save(this, etKey.text.toString(), etStt.text.toString(), etMt.text.toString(), selectedSource())
        if (Prefs.apiKey(this).isBlank()) { toast("Cole sua chave da API OpenAI primeiro"); return }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) proceed() else permLauncher.launch(missing.toTypedArray())
    }

    private fun proceed() {
        if (selectedSource() == "internal") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else startService("mic", 0, null)
    }

    private fun startService(source: String, code: Int, data: Intent?) {
        val i = Intent(this, TranslatorService::class.java)
            .setAction(TranslatorService.ACTION_START)
            .putExtra(TranslatorService.EXTRA_SOURCE, source)
            .putExtra(TranslatorService.EXTRA_CODE, code)
        if (data != null) i.putExtra(TranslatorService.EXTRA_DATA, data)
        ContextCompat.startForegroundService(this, i)
    }

    private fun resize(delta: Float) {
        val s = (tvOut.textSize / resources.displayMetrics.scaledDensity + delta).coerceIn(12f, 40f)
        tvOut.textSize = s
        Prefs.saveTextSize(this, s)
    }

    private fun render() {
        val running = TranslatorService.isRunning
        btnToggle.text = if (running) "⏹ Parar" else "▶ Iniciar"
        config.visibility = if (running) View.GONE else View.VISIBLE
        tvStatus.text = TranscriptStore.status

        val atBottom = !scroll.canScrollVertically(1)
        val grey = 0xFF9E9E9E.toInt()
        val sb = SpannableStringBuilder()
        for (s in TranscriptStore.segments()) {
            val pt = s.pt
            if (s.done && pt != null && pt.isBlank()) continue      // só ruído/hesitação
            if (s.done && s.en.isBlank()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            if (!pt.isNullOrBlank()) {
                val a = sb.length
                sb.append(pt)
                sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
            }
            val b = sb.length
            sb.append(s.en.ifBlank { "…" })
            if (!s.done) sb.append(" …")
            else if (pt == null) sb.append("  ⏳")
            sb.setSpan(ForegroundColorSpan(grey), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.75f), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (sb.isEmpty()) sb.append(
            if (running) "Aguardando fala em inglês…"
            else "Escolha a fonte do áudio e toque em Iniciar.\n\n" +
                "🎙️ Microfone: conversas, aulas, palestras ao redor.\n" +
                "📱 Áudio do celular: vídeos, podcasts e lives tocando no próprio aparelho."
        )
        tvOut.text = sb
        if (atBottom) scroll.post { scroll.scrollTo(0, tvOut.bottom) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

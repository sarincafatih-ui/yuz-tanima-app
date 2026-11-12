package com.fatih.yuztanima

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecognizeActivity : AppCompatActivity() {

    companion object {
        /**
         * Sessiz canlılık kontrolü: kişiye hiçbir şey söylenmeden doğal göz kırpması beklenir.
         * Basılı fotoğrafla açılmayı engeller. Kapatmak için false yapın.
         */
        const val REQUIRE_LIVENESS = true

        private const val MATCH_FRAMES = 3          // Aynı kişi art arda kaç karede eşleşmeli
        private const val UNKNOWN_FRAMES = 12       // Kaç karede eşleşmezse "Tanınmadı"
        private const val LIVENESS_TIMEOUT_MS = 10_000L
    }

    private enum class State { LOADING, SEARCHING, DONE }

    private lateinit var executor: ExecutorService
    private var engine: FaceEngine? = null
    private var people: List<Person> = emptyList()

    private lateinit var txtStatus: TextView
    private lateinit var ring: FaceRingView
    private lateinit var txtDebug: TextView
    private lateinit var panel: View
    private lateinit var iconCircle: FrameLayout
    private lateinit var imgResult: ImageView
    private lateinit var txtResultTitle: TextView
    private lateinit var txtWelcome: TextView
    private lateinit var txtResultName: TextView
    private lateinit var txtResultInfo: TextView
    private lateinit var btnRetry: View
    private lateinit var btnDone: com.google.android.material.button.MaterialButton

    @Volatile private var state = State.LOADING
    private var candidate: Person? = null
    private var streak = 0
    private var unknownCount = 0
    private var bestSim = 0f
    private var sawEyesOpen = false
    private var sawEyesClosed = false
    private var recognizedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognize)

        txtStatus = findViewById(R.id.txtStatus)
        ring = findViewById(R.id.ring)
        txtDebug = findViewById(R.id.txtDebug)
        panel = findViewById(R.id.panelResult)
        iconCircle = findViewById(R.id.iconCircle)
        imgResult = findViewById(R.id.imgResult)
        txtResultTitle = findViewById(R.id.txtResultTitle)
        txtWelcome = findViewById(R.id.txtWelcome)
        txtResultName = findViewById(R.id.txtResultName)
        txtResultInfo = findViewById(R.id.txtResultInfo)
        btnRetry = findViewById(R.id.btnRetry)
        btnDone = findViewById(R.id.btnDone)

        btnDone.setOnClickListener { finish() }
        btnRetry.setOnClickListener { reset() }

        people = FaceStore.all(this)
        txtStatus.text = "Yüz tanıma hazırlanıyor…"

        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                engine = FaceEngine(this)
                runOnUiThread { reset() }
            } catch (e: Exception) {
                runOnUiThread { txtStatus.text = "Model yüklenemedi: ${e.message}" }
            }
        }
        CameraHelper.start(this, findViewById<PreviewView>(R.id.previewView), executor) { onFrame(it) }
    }

    private fun reset() {
        candidate = null
        streak = 0
        unknownCount = 0
        bestSim = 0f
        sawEyesOpen = false
        sawEyesClosed = false
        recognizedAt = 0L
        panel.visibility = View.GONE
        ring.ringColor = ContextCompat.getColor(this, R.color.accent)
        ring.progress = 0f
        txtDebug.text = ""
        txtStatus.text = "Kameraya bakın…"
        state = State.SEARCHING
    }

    /** Kamera thread'inde çalışır. */
    private fun onFrame(bitmap: Bitmap) {
        val eng = engine ?: return
        if (state != State.SEARCHING) return

        val frame = eng.analyze(bitmap)
        if (frame.faceCount == 0) { status("Kameraya bakın…"); streak = 0; return }
        if (frame.faceCount > 1) { status("Kadrajda sadece bir kişi olmalı"); streak = 0; return }
        val crop = frame.aligned ?: run { status("Biraz daha yaklaşın"); return }
        if (frame.size < 0.22f) { status("Biraz daha yaklaşın"); return }

        // Sessiz canlılık: gözlerin hem açık hem kapalı görüldüğü an = doğal göz kırpma
        if (frame.eyesClosed < 0.25f) sawEyesOpen = true
        if (frame.eyesClosed > 0.55f) sawEyesClosed = true
        val alive = !REQUIRE_LIVENESS || (sawEyesOpen && sawEyesClosed)

        if (frame.eyesClosed > 0.55f) return // gözler kapalıyken imza çıkarma
        val frontal = frame.yaw in 0.3f..0.7f

        val match = eng.findBest(eng.embedding(crop), people)
        val person = match.person
        val near = match.nearest
        val dbg = if (near != null) "En yakın: ${near.fullName} · %${(match.similarity * 100).toInt()} (eşik %${(FaceEngine.MATCH_THRESHOLD * 100).toInt()})" else ""
        runOnUiThread { txtDebug.text = dbg }
        if (person != null) {
            unknownCount = 0
            if (person.id == candidate?.id) streak++ else { candidate = person; streak = 1; bestSim = 0f }
            if (match.similarity > bestSim) bestSim = match.similarity
        } else {
            streak = 0
            if (frontal) unknownCount++
        }

        val current = candidate
        when {
            streak >= MATCH_FRAMES && current != null && alive -> {
                state = State.DONE
                val sim = bestSim
                runOnUiThread { showSuccess(current, sim) }
            }
            streak >= MATCH_FRAMES && current != null -> {
                if (recognizedAt == 0L) recognizedAt = System.currentTimeMillis()
                if (System.currentTimeMillis() - recognizedAt > LIVENESS_TIMEOUT_MS) {
                    state = State.DONE
                    runOnUiThread { showFailure("Canlı yüz doğrulanamadı.\nFotoğraf veya ekran kullanılamaz.") }
                } else status("Doğrulanıyor…")
            }
            unknownCount >= UNKNOWN_FRAMES -> {
                state = State.DONE
                runOnUiThread { showFailure("Bu kişi tanımlı değil. Kapı kapalı kaldı.") }
            }
            else -> status("Doğrulanıyor…")
        }
        val p = (streak.coerceAtMost(MATCH_FRAMES).toFloat() / MATCH_FRAMES) * (if (alive) 1f else 0.85f)
        runOnUiThread { if (state == State.SEARCHING) ring.progress = p }
    }

    private fun status(text: String) = runOnUiThread { txtStatus.text = text }

    private fun showSuccess(person: Person, similarity: Float) {
        ring.ringColor = ContextCompat.getColor(this, R.color.accent)
        ring.progress = 1f
        txtStatus.text = "Yüz eşleşti ✓"
        iconCircle.setBackgroundResource(R.drawable.bg_circle_success)
        imgResult.setImageResource(R.drawable.ic_check)
        txtResultTitle.text = "AÇILDI"
        txtResultTitle.setTextColor(ContextCompat.getColor(this, R.color.success))
        txtWelcome.visibility = View.VISIBLE
        txtResultName.visibility = View.VISIBLE
        txtResultName.text = person.fullName
        val time = SimpleDateFormat("HH:mm", Locale("tr")).format(Date())
        txtResultInfo.text = "Eşleşme: %${(similarity * 100).toInt()} · $time"
        btnRetry.visibility = View.GONE
        btnDone.text = "Tamam"
        panel.visibility = View.VISIBLE
    }

    private fun showFailure(message: String) {
        ring.ringColor = ContextCompat.getColor(this, R.color.error_ring)
        ring.progress = 1f
        txtStatus.text = "Eşleşme yok"
        iconCircle.setBackgroundResource(R.drawable.bg_circle_error)
        imgResult.setImageResource(R.drawable.ic_close)
        txtResultTitle.text = "TANINMADI"
        txtResultTitle.setTextColor(ContextCompat.getColor(this, R.color.error))
        txtWelcome.visibility = View.GONE
        txtResultName.visibility = View.GONE
        txtResultInfo.text = message
        btnRetry.visibility = View.VISIBLE
        btnDone.text = "Kapat"
        panel.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.execute { engine?.close(); engine = null }
        executor.shutdown()
    }
}

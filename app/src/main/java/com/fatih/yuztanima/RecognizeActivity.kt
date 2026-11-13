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
         * Sessiz canlılık kontrolü: doğal göz kırpma + sahte yüz tespiti (MiniFASNet).
         * Kişiden hareket istenmez. Kapatmak için false yapın.
         */
        const val REQUIRE_LIVENESS = true

        private const val MATCH_FRAMES = 3          // Aynı kişi art arda kaç karede eşleşmeli
        private const val UNKNOWN_FRAMES = 12       // Kaç karede eşleşmezse "Tanınmadı"
        private const val LIVENESS_TIMEOUT_MS = 15_000L
        private const val BLINK_HINT_MS = 5_000L
        /** Göz kırpma: gözlük vb. için mutlak değer değil, açık-kapalı farkına bakılır. */
        private const val BLINK_RANGE = 0.28f
    }

    private enum class State { LOADING, SEARCHING, DONE }

    private lateinit var executor: ExecutorService
    private var engine: FaceEngine? = null
    private var antiSpoof: AntiSpoof? = null
    private val realScores = ArrayDeque<Float>()
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
    private var lockedFrames = 0
    private var blinkMin = 1f
    private var blinkMax = 0f
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
                antiSpoof = AntiSpoof(this)
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
        lockedFrames = 0
        blinkMin = 1f
        blinkMax = 0f
        realScores.clear()
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

        // Sessiz canlılık: gözlerin açık/kapalı skoru arasında belirgin fark = doğal göz kırpma
        if (frame.eyesClosed < blinkMin) blinkMin = frame.eyesClosed
        if (frame.eyesClosed > blinkMax) blinkMax = frame.eyesClosed
        val blinked = blinkMax - blinkMin >= BLINK_RANGE && blinkMax >= 0.30f

        // Sahte yüz tespiti (MiniFASNet): son 10 karenin ortalaması
        val fas = antiSpoof ?: return
        val real = fas.realScore(bitmap, frame.boxLeft, frame.boxTop, frame.boxWidth, frame.boxHeight)
        realScores.addLast(real)
        if (realScores.size > 10) realScores.removeFirst()
        val avgReal = realScores.average().toFloat()
        val realOk = realScores.size >= 5 && avgReal >= AntiSpoof.REAL_THRESHOLD
        if (REQUIRE_LIVENESS && realScores.size >= 10 && avgReal < 0.30f) {
            state = State.DONE
            runOnUiThread { showFailure("Sahte görüntü algılandı.\nEkran, video veya fotoğraf kullanılamaz.") }
            return
        }
        val alive = !REQUIRE_LIVENESS || (blinked && realOk)

        // Kişi tanındıysa ve sadece canlılık bekleniyorsa, imza çıkarmayı atla:
        // kareler hızlanır, kısa göz kırpmaları kaçmaz.
        val locked = candidate
        if (streak >= MATCH_FRAMES && locked != null) {
            // Her 4 karede bir yüzün hâlâ aynı kişi olduğunu kontrol et
            lockedFrames++
            if (lockedFrames % 4 == 0 && frame.eyesClosed < 0.5f) {
                val again = eng.findBest(eng.embedding(crop), people).person
                if (again?.id != locked.id) { streak = 0; candidate = null; lockedFrames = 0; recognizedAt = 0L; return }
            }
            if (alive) {
                state = State.DONE
                val sim = bestSim
                runOnUiThread { showSuccess(locked, sim) }
                return
            }
            if (recognizedAt == 0L) recognizedAt = System.currentTimeMillis()
            val waited = System.currentTimeMillis() - recognizedAt
            if (waited > LIVENESS_TIMEOUT_MS) {
                state = State.DONE
                val msg = if (blinked) "Sahte görüntü algılandı.\nEkran, video veya fotoğraf kullanılamaz."
                          else "Canlı yüz doğrulanamadı.\nFotoğraf veya ekran kullanılamaz."
                runOnUiThread { showFailure(msg) }
            } else status(if (waited > BLINK_HINT_MS && !blinked) "Doğal şekilde göz kırpın" else "Doğrulanıyor…")
            val dbgLocked = "${locked.fullName} · Canlılık %${(avgReal * 100).toInt()} (eşik %${(AntiSpoof.REAL_THRESHOLD * 100).toInt()}) · Göz kırpma ${if (blinked) "✓" else "bekleniyor"}"
            runOnUiThread { txtDebug.text = dbgLocked; if (state == State.SEARCHING) ring.progress = 0.85f }
            return
        }

        if (frame.eyesClosed > 0.5f) return // gözler kapalıyken imza çıkarma
        val frontal = frame.yaw in 0.3f..0.7f

        val match = eng.findBest(eng.embedding(crop), people)
        val person = match.person
        val near = match.nearest
        val dbg = if (near != null) "En yakın: ${near.fullName} · %${(match.similarity * 100).toInt()} (eşik %${(FaceEngine.MATCH_THRESHOLD * 100).toInt()}) · Canlılık %${(avgReal * 100).toInt()}" else ""
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
                status("Doğrulanıyor…")
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
        executor.execute { engine?.close(); engine = null; antiSpoof?.close(); antiSpoof = null }
        executor.shutdown()
    }
}

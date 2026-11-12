package com.fatih.yuztanima

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Banka tarzı yüz kaydı:
 *  1) Yüzü ovale yerleştir, SABİT dur  → halka dolmaya başlar
 *  2) Başını yavaşça SAĞA çevir
 *  3) Başını yavaşça SOLA çevir
 *  4) Tekrar DÜZ bak                    → halka tamamlanır, kayıt biter
 */
class RegisterActivity : AppCompatActivity() {

    private enum class Step(val samples: Int) { FORM(0), FRONT(3), SIDE1(2), SIDE2(2), FRONT2(1), DONE(0) }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 350L
        private const val STABLE_FRAMES = 4          // "sabit dur" için art arda kaç sabit kare
        private const val STABLE_DELTA = 0.025f      // kareler arası izin verilen kayma
        private const val SIDE_HIGH = 0.63f
        private const val SIDE_LOW = 0.37f
    }

    private val totalSamples = Step.values().sumOf { it.samples }

    private lateinit var executor: ExecutorService
    private var engine: FaceEngine? = null

    private lateinit var ring: FaceRingView
    private lateinit var txtInstruction: TextView
    private lateinit var txtHint: TextView
    private lateinit var txtStep: TextView
    private lateinit var formPanel: View
    private lateinit var editName: EditText
    private lateinit var editSurname: EditText
    private lateinit var btnSave: MaterialButton

    @Volatile private var step = Step.FORM
    private var stepCount = 0
    private val samples = mutableListOf<FloatArray>()
    private var lastSampleAt = 0L
    private var stableFrames = 0
    private var lastCx = -1f
    private var lastCy = -1f
    private var firstSideHigh: Boolean? = null   // SIDE1'de hangi tarafa dönüldü
    private var name = ""
    private var surname = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        ring = findViewById(R.id.ring)
        txtInstruction = findViewById(R.id.txtInstruction)
        txtHint = findViewById(R.id.txtHint)
        txtStep = findViewById(R.id.txtStep)
        formPanel = findViewById(R.id.formPanel)
        editName = findViewById(R.id.editName)
        editSurname = findViewById(R.id.editSurname)
        btnSave = findViewById(R.id.btnSave)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        btnSave.isEnabled = false
        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                engine = FaceEngine(this)
                runOnUiThread {
                    btnSave.isEnabled = true
                    txtInstruction.text = "Ad ve soyadı yazın"
                    txtHint.text = "Sonra yüz taramasını başlatın"
                }
            } catch (e: Exception) {
                runOnUiThread { txtInstruction.text = "Model yüklenemedi: ${e.message}" }
            }
        }

        CameraHelper.start(this, findViewById<PreviewView>(R.id.previewView), executor) { onFrame(it) }
        btnSave.setOnClickListener { startScan() }
    }

    private fun startScan() {
        name = editName.text.toString().trim()
        surname = editSurname.text.toString().trim()
        if (name.isEmpty() || surname.isEmpty()) {
            Toast.makeText(this, "Ad ve soyad girin", Toast.LENGTH_SHORT).show()
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editSurname.windowToken, 0)
        formPanel.visibility = View.GONE
        synchronized(samples) { samples.clear() }
        ring.progress = 0f
        firstSideHigh = null
        goTo(Step.FRONT)
    }

    private fun goTo(s: Step) {
        stepCount = 0
        stableFrames = 0
        lastSampleAt = 0L
        step = s
        runOnUiThread {
            when (s) {
                Step.FRONT -> { txtInstruction.text = "Yüzünüzü ovale yerleştirin\nve sabit durun"; txtStep.text = "1 / 4" }
                Step.SIDE1 -> { txtInstruction.text = "Başınızı yavaşça\nSAĞA çevirin"; txtStep.text = "2 / 4" }
                Step.SIDE2 -> { txtInstruction.text = "Şimdi yavaşça\nSOLA çevirin"; txtStep.text = "3 / 4" }
                Step.FRONT2 -> { txtInstruction.text = "Tekrar kameraya\ndüz bakın"; txtStep.text = "4 / 4" }
                else -> {}
            }
            txtHint.text = ""
        }
    }

    /** Kamera thread'inde çalışır. */
    private fun onFrame(bitmap: Bitmap) {
        val eng = engine ?: return
        val s = step
        if (s == Step.FORM || s == Step.DONE) return

        val f = eng.analyze(bitmap)
        val aligned = f.aligned
        when {
            f.faceCount == 0 -> { hint("Yüz görünmüyor"); stableFrames = 0; return }
            f.faceCount > 1 -> { hint("Kadrajda sadece bir kişi olmalı"); stableFrames = 0; return }
            aligned == null || f.size < 0.28f -> { hint("Biraz daha yaklaşın"); stableFrames = 0; return }
            f.size > 0.85f -> { hint("Biraz uzaklaşın"); stableFrames = 0; return }
            f.cx < 0.33f || f.cx > 0.67f || f.cy < 0.22f || f.cy > 0.60f -> {
                hint("Yüzünüzü ovalin ortasına getirin"); stableFrames = 0; return
            }
            f.eyesClosed > 0.5f -> { hint("Gözlerinizi açık tutun"); return }
        }

        // Sabitlik
        val stable = lastCx >= 0 && abs(f.cx - lastCx) < STABLE_DELTA && abs(f.cy - lastCy) < STABLE_DELTA
        lastCx = f.cx; lastCy = f.cy
        stableFrames = if (stable) stableFrames + 1 else 0

        val poseOk = when (s) {
            Step.FRONT, Step.FRONT2 -> {
                if (f.yaw !in 0.40f..0.60f) { hint("Kameraya düz bakın"); false }
                else if (stableFrames < STABLE_FRAMES) { hint("Sabit durun…"); false }
                else true
            }
            Step.SIDE1 -> {
                val high = f.yaw > SIDE_HIGH; val low = f.yaw < SIDE_LOW
                if (high || low) { if (firstSideHigh == null) firstSideHigh = high; high == firstSideHigh }
                else { hint("Biraz daha çevirin"); false }
            }
            Step.SIDE2 -> {
                val wantHigh = firstSideHigh == false
                val ok = if (wantHigh) f.yaw > SIDE_HIGH else f.yaw < SIDE_LOW
                if (!ok) hint(if (f.yaw in SIDE_LOW..SIDE_HIGH) "Biraz daha çevirin" else "Diğer tarafa çevirin")
                ok
            }
            else -> false
        }
        if (!poseOk) return

        val now = System.currentTimeMillis()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now

        val emb = eng.embedding(aligned!!)
        val total = synchronized(samples) { samples.add(emb); samples.size }
        stepCount++
        runOnUiThread {
            ring.progress = total.toFloat() / totalSamples
            txtHint.text = "Harika"
        }

        if (stepCount >= s.samples) {
            when (s) {
                Step.FRONT -> goTo(Step.SIDE1)
                Step.SIDE1 -> goTo(Step.SIDE2)
                Step.SIDE2 -> goTo(Step.FRONT2)
                Step.FRONT2 -> finishScan(eng)
                else -> {}
            }
        }
    }

    private fun finishScan(eng: FaceEngine) {
        step = Step.DONE
        val list = synchronized(samples) { samples.toList() }
        val existing = eng.findBest(list[0], FaceStore.all(this)).person
        FaceStore.add(this, name, surname, list)
        runOnUiThread {
            ring.progress = 1f
            txtStep.text = ""
            txtInstruction.text = "Kaydedildi ✓"
            txtHint.text = if (existing != null) "$name $surname (not: ${existing.fullName} ile benziyor)"
                           else "$name $surname"
            ring.postDelayed({ finish() }, 1500)
        }
    }

    private fun hint(text: String) = runOnUiThread { txtHint.text = text }

    override fun onDestroy() {
        super.onDestroy()
        executor.execute { engine?.close(); engine = null }
        executor.shutdown()
    }
}

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
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RegisterActivity : AppCompatActivity() {

    companion object {
        const val SAMPLE_COUNT = 5
        const val SAMPLE_INTERVAL_MS = 400L
    }

    private lateinit var executor: ExecutorService
    private var engine: FaceEngine? = null

    private lateinit var txtStatus: TextView
    private lateinit var txtProgress: TextView
    private lateinit var txtHint: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var editName: EditText
    private lateinit var editSurname: EditText
    private lateinit var btnSave: MaterialButton

    @Volatile private var capturing = false
    private val samples = mutableListOf<FloatArray>()
    private var lastSampleAt = 0L
    private var name = ""
    private var surname = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        txtStatus = findViewById(R.id.txtStatus)
        txtProgress = findViewById(R.id.txtProgress)
        txtHint = findViewById(R.id.txtHint)
        progress = findViewById(R.id.progress)
        editName = findViewById(R.id.editName)
        editSurname = findViewById(R.id.editSurname)
        btnSave = findViewById(R.id.btnSave)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        btnSave.isEnabled = false
        txtStatus.text = "Yüz tanıma hazırlanıyor…"

        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                engine = FaceEngine(this)
                runOnUiThread {
                    btnSave.isEnabled = true
                    txtStatus.text = "Ad ve soyadı yazıp “Yüzü Kaydet”e basın"
                }
            } catch (e: Exception) {
                runOnUiThread { txtStatus.text = "Model yüklenemedi: ${e.message}" }
            }
        }

        CameraHelper.start(this, findViewById<PreviewView>(R.id.previewView), executor) { onFrame(it) }

        btnSave.setOnClickListener { startCapture() }
    }

    private fun startCapture() {
        name = editName.text.toString().trim()
        surname = editSurname.text.toString().trim()
        if (name.isEmpty() || surname.isEmpty()) {
            Toast.makeText(this, "Ad ve soyad girin", Toast.LENGTH_SHORT).show()
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editSurname.windowToken, 0)
        synchronized(samples) { samples.clear() }
        lastSampleAt = 0L
        progress.progress = 0
        txtProgress.text = "0 / $SAMPLE_COUNT"
        txtStatus.text = "Yüz örnekleri alınıyor…"
        txtHint.text = "Kameraya düz bakın, başınızı hafifçe çevirin."
        btnSave.isEnabled = false
        capturing = true
    }

    /** Kamera thread'inde çalışır. */
    private fun onFrame(bitmap: Bitmap) {
        val eng = engine ?: return
        if (!capturing) return
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return

        val frame = eng.analyze(bitmap)
        val crop = frame.crop
        when {
            frame.faceCount == 0 -> hint("Yüz görünmüyor, kameraya bakın")
            frame.faceCount > 1 -> hint("Kadrajda sadece bir kişi olmalı")
            crop == null -> hint("Biraz daha yaklaşın")
            frame.yaw < 0.25f || frame.yaw > 0.75f -> hint("Kameraya daha düz bakın")
            frame.eyesClosed > 0.5f -> hint("Gözlerinizi açık tutun")
            else -> {
                val emb = eng.embedding(crop)
                lastSampleAt = now
                val count = synchronized(samples) { samples.add(emb); samples.size }
                runOnUiThread {
                    progress.setProgressCompat(count, true)
                    txtProgress.text = "$count / $SAMPLE_COUNT"
                    txtHint.text = "Harika, devam edin…"
                }
                if (count >= SAMPLE_COUNT) {
                    capturing = false
                    val list = synchronized(samples) { samples.toList() }
                    val existing = eng.findBest(list[0], FaceStore.all(this)).person
                    FaceStore.add(this, name, surname, list)
                    runOnUiThread {
                        val msg = if (existing != null)
                            "$name $surname kaydedildi (not: ${existing.fullName} ile benziyor)"
                        else "$name $surname kaydedildi"
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }

    private fun hint(text: String) = runOnUiThread { txtHint.text = text }

    override fun onDestroy() {
        super.onDestroy()
        executor.execute { engine?.close(); engine = null }
        executor.shutdown()
    }
}

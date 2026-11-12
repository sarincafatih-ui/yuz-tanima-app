package com.fatih.yuztanima

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bir kamera karesinde bulunan yüz bilgisi. */
data class FaceFrame(
    val faceCount: Int,
    val crop: Bitmap?,          // Yüzün kare kesilmiş hali (tek yüz varsa)
    val yaw: Float,             // 0.5 = tam karşıya bakıyor; <0.35 sola, >0.65 sağa dönük
    val eyesClosed: Float       // 0..1, iki gözün kapalılık skoru (göz kırpma)
)

data class MatchResult(val person: Person?, val similarity: Float)

/**
 * Açık kaynak yüz motoru:
 *  - MediaPipe Face Landmarker → yüz bulma, 478 nokta, göz kırpma skoru
 *  - TensorFlow Lite MobileFaceNet → 192 boyutlu yüz imzası
 */
class FaceEngine(context: Context) {

    companion object {
        /** Aynı kişi sayılması için gereken benzerlik (0..1). Yükseltirseniz daha sıkı olur. */
        const val MATCH_THRESHOLD = 0.70f
        private const val INPUT_SIZE = 112
    }

    private val landmarker: FaceLandmarker
    private val interpreter: Interpreter
    private val embeddingSize: Int

    init {
        val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(2)
            .setOutputFaceBlendshapes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)

        val fd = context.assets.openFd("mobile_face_net.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        embeddingSize = interpreter.getOutputTensor(0).shape()[1]
    }

    /** Kareyi analiz eder: kaç yüz var, yüz kesiti, baş yönü, göz kırpma. */
    fun analyze(bitmap: Bitmap): FaceFrame {
        val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val faces = result.faceLandmarks()
        if (faces.size != 1) return FaceFrame(faces.size, null, 0.5f, 0f)

        val lm = faces[0]
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        lm.forEach {
            minX = min(minX, it.x()); maxX = max(maxX, it.x())
            minY = min(minY, it.y()); maxY = max(maxY, it.y())
        }

        // Baş yönü: burun ucunun (1) yüz kenarları (234, 454) arasındaki konumu
        val nose = lm[1].x()
        val a = lm[234].x(); val b = lm[454].x()
        val left = min(a, b); val right = max(a, b)
        val yaw = if (right - left > 1e-4f) (nose - left) / (right - left) else 0.5f

        // Göz kırpma skoru (MediaPipe blendshapes)
        var blinkL = 0f; var blinkR = 0f
        val shapes = result.faceBlendshapes()
        if (shapes.isPresent && shapes.get().isNotEmpty()) {
            shapes.get()[0].forEach { c ->
                when (c.categoryName()) {
                    "eyeBlinkLeft" -> blinkL = c.score()
                    "eyeBlinkRight" -> blinkR = c.score()
                }
            }
        }

        // Yüzü kare olarak kes
        val w = bitmap.width; val h = bitmap.height
        val cx = (minX + maxX) / 2f * w
        val cy = (minY + maxY) / 2f * h
        val size = max((maxX - minX) * w, (maxY - minY) * h)
        val half = size / 2f
        val rect = Rect(
            (cx - half).toInt().coerceAtLeast(0),
            (cy - half).toInt().coerceAtLeast(0),
            (cx + half).toInt().coerceAtMost(w),
            (cy + half).toInt().coerceAtMost(h)
        )
        val crop = if (rect.width() > 40 && rect.height() > 40)
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        else null

        return FaceFrame(1, crop, yaw, min(blinkL, blinkR))
    }

    /** Yüz kesitinden 192 boyutlu, normalize edilmiş yüz imzası çıkarır. */
    fun embedding(face: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(face, INPUT_SIZE, INPUT_SIZE, true)
        val input = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        val output = Array(1) { FloatArray(embeddingSize) }
        interpreter.run(input, output)
        return normalize(output[0])
    }

    /** Kayıtlı kişiler arasında en benzerini bulur. */
    fun findBest(embedding: FloatArray, people: List<Person>): MatchResult {
        var best: Person? = null
        var bestSim = -1f
        for (p in people) {
            for (e in p.embeddings) {
                val s = cosine(embedding, e)
                if (s > bestSim) { bestSim = s; best = p }
            }
        }
        return MatchResult(if (bestSim >= MATCH_THRESHOLD) best else null, bestSim)
    }

    fun close() {
        landmarker.close()
        interpreter.close()
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

package com.fatih.yuztanima

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bir kamera karesinde bulunan yüz bilgisi. */
data class FaceFrame(
    val faceCount: Int,
    val aligned: Bitmap?,       // Gözler yatay, 112x112 hizalanmış yüz (tek yüz varsa)
    val yaw: Float,             // 0.5 = tam karşıya bakıyor; küçük/büyük = yana dönük
    val eyesClosed: Float,      // 0..1, iki gözün ortalama kapalılık skoru (göz kırpma)
    val cx: Float,              // Yüz merkezi (0..1, görüntü genişliğine oranla)
    val cy: Float,              // Yüz merkezi (0..1, görüntü yüksekliğine oranla)
    val size: Float             // Yüz genişliği / görüntü genişliği
)

data class MatchResult(val person: Person?, val similarity: Float, val nearest: Person?)

/**
 * Açık kaynak yüz motoru:
 *  - MediaPipe Face Landmarker → yüz bulma, 478 nokta, göz kırpma skoru
 *  - TensorFlow Lite MobileFaceNet → 192 boyutlu yüz imzası
 * Yüz, imza çıkarılmadan önce göz ve ağız noktalarına göre hizalanır
 * (MobileFaceNet hizalanmış yüzlerle eğitildiği için tanıma çok daha tutarlı olur).
 */
class FaceEngine(context: Context) {

    companion object {
        /** Aynı kişi sayılması için gereken benzerlik (0..1). Yükseltirseniz daha sıkı olur. */
        const val MATCH_THRESHOLD = 0.60f
        private const val SIZE = 112

        // ArcFace/MobileFaceNet 112x112 standart yüz şablonu
        private const val T_EYE_DIST = 35.2f   // iki göz merkezi arası
        private const val T_EYE_MOUTH = 40.4f  // göz ortası → ağız ortası
        private const val T_EYE_X = 56f
        private const val T_EYE_Y = 51.6f
    }

    private val landmarker: FaceLandmarker
    private val interpreter: Interpreter
    private val embeddingSize: Int
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

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

    /** Kareyi analiz eder: kaç yüz var, hizalanmış yüz, baş yönü, göz kırpma, konum. */
    fun analyze(bitmap: Bitmap): FaceFrame {
        val result = landmarker.detect(BitmapImageBuilder(bitmap).build())
        val faces = result.faceLandmarks()
        if (faces.size != 1) return FaceFrame(faces.size, null, 0.5f, 0f, 0.5f, 0.5f, 0f)

        val lm = faces[0]
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

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

        // ---- Hizalama: göz merkezleri yatay, şablon konumuna ölçekle ----
        fun px(i: Int) = lm[i].x() * w
        fun py(i: Int) = lm[i].y() * h
        val e1x = (px(33) + px(133)) / 2f; val e1y = (py(33) + py(133)) / 2f
        val e2x = (px(362) + px(263)) / 2f; val e2y = (py(362) + py(263)) / 2f
        val lx: Float; val ly: Float; val rx: Float; val ry: Float
        if (e1x <= e2x) { lx = e1x; ly = e1y; rx = e2x; ry = e2y } else { lx = e2x; ly = e2y; rx = e1x; ry = e1y }
        val ecx = (lx + rx) / 2f; val ecy = (ly + ry) / 2f
        val mcx = (px(61) + px(291)) / 2f; val mcy = (py(61) + py(291)) / 2f
        val eyeDist = hypot(rx - lx, ry - ly)
        val eyeMouth = hypot(mcx - ecx, mcy - ecy)

        val aligned = if (eyeDist > 12f && eyeMouth > 12f) {
            val angle = Math.toDegrees(atan2((ry - ly).toDouble(), (rx - lx).toDouble())).toFloat()
            // Yana dönünce göz arası kısalır; iki ölçünün büyüğünü kullanarak ölçek sabit kalır
            val faceScale = max(eyeDist / T_EYE_DIST, eyeMouth / T_EYE_MOUTH)
            val s = 1f / faceScale
            val m = Matrix().apply {
                postTranslate(-ecx, -ecy)
                postRotate(-angle)
                postScale(s, s)
                postTranslate(T_EYE_X, T_EYE_Y)
            }
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bitmap, m, paint)
            }
        } else null

        return FaceFrame(
            faceCount = 1,
            aligned = aligned,
            yaw = yaw,
            eyesClosed = (blinkL + blinkR) / 2f,
            cx = (minX + maxX) / 2f,
            cy = (minY + maxY) / 2f,
            size = maxX - minX
        )
    }

    /** Hizalanmış 112x112 yüzden normalize edilmiş yüz imzası çıkarır. */
    fun embedding(face: Bitmap): FloatArray {
        val img = if (face.width == SIZE && face.height == SIZE) face
                  else Bitmap.createScaledBitmap(face, SIZE, SIZE, true)
        val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(SIZE * SIZE)
        img.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
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
        return MatchResult(if (bestSim >= MATCH_THRESHOLD) best else null, bestSim, best)
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

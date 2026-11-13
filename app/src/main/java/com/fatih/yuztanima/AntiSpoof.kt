package com.fatih.yuztanima

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Açık kaynak sahte yüz tespiti: Silent-Face-Anti-Spoofing / MiniFASNet (Minivision, Apache 2.0).
 * Kişiden hareket istemez. Görüntünün canlı yüz mü, yoksa ekran / kâğıt mı olduğunu
 * doku, yansıma ve çerçeve izlerinden tahmin eder. 0..1 "gerçek yüz" skoru döner.
 *
 * İki model birlikte kullanılır (yüzün 2.7 ve 4.0 katı genişlikte çevresi), sonuçların ortalaması alınır.
 */
class AntiSpoof(context: Context) {

    companion object {
        /** Bu değerin altındaki ortalama skor "sahte görüntü" sayılır. */
        const val REAL_THRESHOLD = 0.60f
        private const val SIZE = 80
    }

    private val env = OrtEnvironment.getEnvironment()
    private val models: List<Pair<Float, OrtSession>> = listOf(
        2.7f to load(context, "2.7_80x80_MiniFASNetV2.onnx"),
        4.0f to load(context, "4_0_0_80x80_MiniFASNetV1SE.onnx")
    )

    private fun load(context: Context, name: String): OrtSession {
        val bytes = context.assets.open(name).use { it.readBytes() }
        return env.createSession(bytes, OrtSession.SessionOptions())
    }

    /**
     * @param bitmap kamera karesi
     * @param left/top/width/height yüz kutusu (piksel)
     */
    fun realScore(bitmap: Bitmap, left: Float, top: Float, width: Float, height: Float): Float {
        var sum = 0f
        for ((scale, session) in models) {
            val crop = cropAround(bitmap, left, top, width, height, scale)
            val input = toInput(crop)
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
                session.run(mapOf("input" to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (r[0].value as Array<FloatArray>)[0]
                    sum += softmax(logits)[1] // sınıf 1 = gerçek yüz
                }
            }
        }
        return sum / models.size
    }

    /** Orijinal projedeki CropImage._get_new_box ile aynı kırpma. */
    private fun cropAround(bmp: Bitmap, x: Float, y: Float, bw: Float, bh: Float, wanted: Float): Bitmap {
        val srcW = bmp.width.toFloat(); val srcH = bmp.height.toFloat()
        val scale = min((srcH - 1) / bh, min((srcW - 1) / bw, wanted))
        val nw = bw * scale; val nh = bh * scale
        val cx = x + bw / 2; val cy = y + bh / 2
        var l = cx - nw / 2; var t = cy - nh / 2; var r = cx + nw / 2; var b = cy + nh / 2
        if (l < 0) { r -= l; l = 0f }
        if (t < 0) { b -= t; t = 0f }
        if (r > srcW - 1) { l -= r - srcW + 1; r = srcW - 1 }
        if (b > srcH - 1) { t -= b - srcH + 1; b = srcH - 1 }
        val li = max(0, l.toInt()); val ti = max(0, t.toInt())
        val wi = max(1, min(bmp.width - li, (r - l).toInt() + 1))
        val hi = max(1, min(bmp.height - ti, (b - t).toInt() + 1))
        val cropped = Bitmap.createBitmap(bmp, li, ti, wi, hi)
        return Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true)
    }

    /** Model girişi: BGR sırası, 0..255 değer, CHW düzeni (orijinal eğitimle aynı). */
    private fun toInput(img: Bitmap): FloatBuffer {
        val px = IntArray(SIZE * SIZE)
        img.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val p = px[i]
            buf.put(i, (p and 0xFF).toFloat())                       // B
            buf.put(plane + i, ((p shr 8) and 0xFF).toFloat())       // G
            buf.put(2 * plane + i, ((p shr 16) and 0xFF).toFloat())  // R
        }
        buf.rewind()
        return buf
    }

    private fun softmax(z: FloatArray): FloatArray {
        val m = z.max()
        val e = FloatArray(z.size) { exp(z[it] - m) }
        val s = e.sum()
        return FloatArray(z.size) { e[it] / s }
    }

    fun close() {
        models.forEach { it.second.close() }
    }
}

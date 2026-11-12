package com.fatih.yuztanima

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

/** Ön kamerayı açar; her kareyi ayna görüntüsü (selfie gibi) Bitmap olarak verir. */
object CameraHelper {

    fun start(
        owner: LifecycleOwner,
        previewView: PreviewView,
        executor: ExecutorService,
        onFrame: (Bitmap) -> Unit
    ) {
        val context = previewView.context
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    onFrame(toUprightMirrored(proxy))
                } catch (_: Exception) {
                } finally {
                    proxy.close()
                }
            }
            val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun toUprightMirrored(proxy: ImageProxy): Bitmap {
        val bmp = proxy.toBitmap()
        val m = Matrix()
        m.postRotate(proxy.imageInfo.rotationDegrees.toFloat())
        m.postScale(-1f, 1f) // ayna: kullanıcının sağı ekranda da sağ
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

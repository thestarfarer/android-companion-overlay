package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless back-camera still capture for "look at this" shots.
 *
 * Produces the same artifact as [ScreenshotManager] — a base64-encoded JPEG —
 * so the rest of the pipeline ([CompanionOverlayService.dispatchCapturedImage]
 * → Claude image content) is reused unchanged. No preview is shown: the user
 * points the phone and triggers, we grab one frame.
 *
 * CameraX requires a [LifecycleOwner] to bind use cases, which a [android.app.Service]
 * is not — so we spin up a throwaway [LifecycleRegistry], drive it to RESUMED for
 * the capture, and tear it down to DESTROYED afterward (which unbinds the camera).
 *
 * The captured JPEG is saved to a temp file (the file-based path guarantees a real
 * JPEG, unlike the in-memory ImageProxy whose format can vary), then re-encoded
 * upright via EXIF and downscaled to keep the payload reasonable for the vision API.
 *
 * [capture] must be called on the main thread — CameraX binds on main.
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "Camera"
        private const val MAX_DIMENSION = 1568   // Claude vision sweet spot (long edge)
        private const val JPEG_QUALITY = 95
        private const val CAPTURE_TIMEOUT_MS = 9000L
        // Headless capture has no preview stream to drive continuous autofocus, so
        // give the lens a beat to converge after binding before we take the shot.
        private const val FOCUS_SETTLE_MS = 600L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Minimal LifecycleOwner so CameraX can bind from a Service. */
    private class CaptureLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * Capture one still from the back camera and return it as a base64 JPEG,
     * or null on any failure. The callback runs on the main thread exactly once.
     */
    fun capture(callback: (String?) -> Unit) {
        val done = AtomicBoolean(false)
        val lifecycleOwner = CaptureLifecycle()
        var provider: ProcessCameraProvider? = null

        fun finish(base64: String?) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.post {
                try { provider?.unbindAll() } catch (_: Exception) {}
                lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
                callback(base64)
            }
        }

        // Never leave the camera bound if takePicture never calls back.
        mainHandler.postDelayed({
            DebugLog.log(TAG, "Capture timed out")
            finish(null)
        }, CAPTURE_TIMEOUT_MS)

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                provider!!.unbindAll()
                provider!!.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageCapture
                )

                // Let autofocus/exposure converge, then capture.
                mainHandler.postDelayed({
                    if (done.get()) return@postDelayed
                    val tempFile = File.createTempFile("capture", ".jpg", context.cacheDir)
                    val output = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                    imageCapture.takePicture(
                        output,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                val base64 = try {
                                    encodeUpright(tempFile)
                                } catch (e: Exception) {
                                    DebugLog.log(TAG, "Encode failed: ${e.message}")
                                    null
                                } finally {
                                    tempFile.delete()
                                }
                                finish(base64)
                            }

                            override fun onError(exc: ImageCaptureException) {
                                DebugLog.log(TAG, "Capture error: ${exc.message}")
                                tempFile.delete()
                                finish(null)
                            }
                        }
                    )
                }, FOCUS_SETTLE_MS)
            } catch (e: Exception) {
                DebugLog.log(TAG, "Camera bind failed: ${e.message}")
                finish(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Read the saved JPEG, apply EXIF rotation, downscale, re-encode → base64. */
    private fun encodeUpright(file: File): String {
        val path = file.absolutePath

        // Decode bounds first so we can subsample large sensor output for memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) > MAX_DIMENSION) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(path, opts)
            ?: throw IllegalStateException("decode returned null")

        val rotation = when (ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        val scale = MAX_DIMENSION.toFloat() / maxOf(bmp.width, bmp.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        if (!matrix.isIdentity) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

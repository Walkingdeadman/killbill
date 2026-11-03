package com.killbill.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * MediaProjection 截屏：VirtualDisplay + ImageReader 抓一帧即释放。
 */
object ScreenCapturer {

    private const val CAPTURE_TIMEOUT_MS = 6000L

    /** 截一帧，超时/失败返回 null。必须在主线程调用。 */
    suspend fun capture(projection: MediaProjection, ctx: Context): Bitmap? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val wm = ctx.getSystemService(WindowManager::class.java)
            val (w, h) = screenSize(wm)
            if (w <= 0 || h <= 0) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val dpi = ctx.resources.displayMetrics.densityDpi

            var imageReader: ImageReader? = null
            var virtualDisplay: VirtualDisplay? = null
            val done = AtomicBoolean(false)

            fun cleanup() {
                runCatching { virtualDisplay?.release() }
                runCatching { imageReader?.close() }
                virtualDisplay = null
                imageReader = null
            }

            val timeoutTask = Runnable {
                if (done.compareAndSet(false, true)) {
                    cleanup()
                    if (cont.isActive) cont.resume(null)
                }
            }

            try {
                imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                imageReader!!.setOnImageAvailableListener({ reader ->
                    if (done.get()) return@setOnImageAvailableListener
                    val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    if (!done.compareAndSet(false, true)) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    handler.removeCallbacks(timeoutTask)
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * w
                        var bmp = Bitmap.createBitmap(
                            w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        if (bmp.width != w) {
                            val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                            bmp.recycle()
                            bmp = cropped
                        }
                        if (cont.isActive) cont.resume(bmp) else bmp.recycle()
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    } finally {
                        image.close()
                        cleanup()
                    }
                }, handler)

                virtualDisplay = projection.createVirtualDisplay(
                    "killbill-capture", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface, null, handler
                )
                handler.postDelayed(timeoutTask, CAPTURE_TIMEOUT_MS)
            } catch (e: Exception) {
                if (done.compareAndSet(false, true)) {
                    cleanup()
                    if (cont.isActive) cont.resume(null)
                }
            }

            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutTask)
                if (done.compareAndSet(false, true)) cleanup()
            }
        }

    /** 屏幕物理尺寸，兼容 API 26-29（currentWindowMetrics 是 API 30+） */
    private fun screenSize(wm: WindowManager): Pair<Int, Int> {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.let { d ->
                val p = android.graphics.Point()
                d.getRealSize(p)
                p.x to p.y
            }
        }
    }

    /** Bitmap → 长边压到 maxSide、JPEG quality 压缩 → 裸 base64（控制 token 成本，R8） */
    fun encodeJpeg(bmp: Bitmap, maxSide: Int = 1280, quality: Int = 80): String {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bmp
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}

package com.killbill.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 本地中文 OCR（ML Kit，端侧推理 ~0.3s，零网络，模型内置 APK 无需 GMS）。
 *
 * 防御设计（用户真机踩过）：个别设备上 ML Kit 的 success/failure 回调都不来，
 * 协程傻等 → 悬浮球永远转圈。这里加 20s 硬超时，超时即判定引擎坏死，
 * 本会话禁用本地 OCR（broken=true），上层自动回退全图模型链路，绝不再傻等。
 */
object OcrExtractor {

    private const val TIMEOUT_MS = 20_000L

    @Volatile
    var broken = false
        private set

    @Volatile
    var lastError: String? = null

    /** 识别失败/引擎不可用/超时返回 null */
    suspend fun recognize(ctx: Context, bmp: Bitmap): List<String>? {
        if (broken) return null
        return withContext(Dispatchers.IO) {
            val completed = AtomicBoolean(false)
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<List<String>?> { cont ->
                    var rec: TextRecognizer? = null
                    try {
                        rec = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                        val recognizer = rec
                        recognizer.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener { res ->
                                completed.set(true)
                                val lines = res.textBlocks
                                    .flatMap { it.lines }
                                    .map { it.text }
                                    .filter { it.isNotBlank() }
                                runCatching { recognizer.close() }
                                if (cont.isActive) cont.resume(lines)
                            }
                            .addOnFailureListener { e ->
                                completed.set(true)
                                lastError = e.message ?: e.javaClass.simpleName
                                runCatching { recognizer.close() }
                                if (cont.isActive) cont.resume(null)
                            }
                        cont.invokeOnCancellation { runCatching { recognizer.close() } }
                    } catch (t: Throwable) {
                        completed.set(true)
                        lastError = t.message ?: t.javaClass.simpleName
                        runCatching { rec?.close() }
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (!completed.get()) {
                broken = true
                lastError = "OCR ${TIMEOUT_MS / 1000}s 无回调，引擎判定坏死，本次会话禁用本地 OCR"
            }
            result
        }
    }

    /** 设置页诊断：跑一张小图探引擎活不活 */
    suspend fun probe(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val r = recognize(ctx, bmp)
        bmp.recycle()
        r != null
    }
}

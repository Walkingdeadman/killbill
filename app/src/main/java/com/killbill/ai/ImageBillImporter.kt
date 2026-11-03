package com.killbill.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.killbill.capture.ScreenshotFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册批量导入的本地识别链路：解码 → ML Kit 中文 OCR → 本地规则解析。
 *
 * 全程零网络：用户明确要的是"本地 OCR 识别后确认录入"，
 * 不送千问（网关排队一两分钟，批量场景不可用）。
 * OCR 失败/规则没认出都返回可展示的原因，由确认页让用户手动补全或跳过。
 */
object ImageBillImporter {

    data class Outcome(
        /** 识别出的草稿；没认出账单时为 null */
        val draft: BillDraft? = null,
        /** 规则解析把握大（有商户或类别线索）；false 时确认页提示核对 */
        val confident: Boolean = false,
        /** 失败/降级原因，直接展示在确认页 */
        val error: String? = null,
        /** 缩略图，供确认页辨认是哪张图；解码失败为 null */
        val thumbnail: Bitmap? = null,
    )

    suspend fun analyze(ctx: Context, uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val decoded: Bitmap = ScreenshotFinder.decodeSampled(ctx, uri)
            ?: return@withContext Outcome(error = "图片打不开")
        // 相册里的相机照片常带 EXIF 旋转，不摆正 OCR 必错
        val bmp = runCatching { applyExifRotation(ctx, uri, decoded) }.getOrDefault(decoded)
        val thumb = runCatching { makeThumbnail(bmp) }.getOrNull()
        val lines = try {
            OcrExtractor.recognize(ctx, bmp)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        when {
            lines == null -> Outcome(
                error = OcrExtractor.lastError?.takeIf { it.isNotBlank() } ?: "本地 OCR 不可用",
                thumbnail = thumb,
            )

            lines.isEmpty() -> Outcome(error = "没识别到文字", thumbnail = thumb)

            else -> {
                val parsed = TextBillParser.parse(lines)
                if (parsed == null) {
                    Outcome(error = "没认出账单，可手动补全", thumbnail = thumb)
                } else {
                    Outcome(
                        draft = parsed.draft,
                        confident = parsed.confident,
                        thumbnail = thumb,
                    )
                }
            }
        }
    }

    /** 按 EXIF 方向把图摆正；读不到 EXIF/本来正向就原样返回 */
    private fun applyExifRotation(ctx: Context, uri: Uri, src: Bitmap): Bitmap {
        val orientation = ctx.contentResolver.openInputStream(uri)?.use { input ->
            runCatching {
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: return src

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    /** 缩略图长边压到 240px；失败不影响识别主流程 */
    private fun makeThumbnail(src: Bitmap): Bitmap? {
        val maxSide = 240
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val scale = maxSide.toFloat() / maxOf(w, h)
        if (scale >= 1f) return src.copy(Bitmap.Config.ARGB_8888, false)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, tw, th, true)
    }
}

package com.killbill.capture

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册截屏猎手：从 MediaStore 找用户最近的系统截屏。
 *
 * 这是主取图路径——用户自己截屏（系统快捷键/三指下滑），点悬浮球，
 * 这里捞出最近 N 分钟内的那张截屏。无 MediaProjection、无录制指示、无重复授权。
 */
object ScreenshotFinder {

    data class Found(
        val uri: Uri,
        val dateAddedSec: Long,
        val name: String,
    )

    /** 当前 Android 版本需要的相册读取权限 */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasReadPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * 找最近 withinMillis 时间窗内的最新一张截屏。找不到返回 null（上层降级直截屏）。
     * 全程 runCatching：个别 ROM 的 MediaStore 列缺失/权限抽风不许崩服务。
     */
    suspend fun findLatest(ctx: Context, withinMillis: Long = DEFAULT_WINDOW_MS): Found? =
        withContext(Dispatchers.IO) {
            runCatching {
                val cutoffSec = System.currentTimeMillis() / 1000 - withinMillis / 1000
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA,
                )
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                    arrayOf(cutoffSec.toString()),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 30"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val pathCol = cursor.getColumnIndex(
                        if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH
                        else MediaStore.Images.Media.DATA
                    )
                    while (cursor.moveToNext()) {
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                        val path = if (pathCol >= 0) cursor.getString(pathCol) else null
                        if (isScreenshotEntry(name, path)) {
                            return@use Found(
                                uri = ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    cursor.getLong(idCol)
                                ),
                                dateAddedSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0,
                                name = name ?: "screenshot",
                            )
                        }
                    }
                    null
                }
            }.getOrNull()
        }

    /**
     * 按文件名/路径判断是否截屏（纯函数，单测覆盖）。
     * 兼容：原生/三星 Screenshot_*、ColorOS/MIUI 截屏*、ScreenCapture、Screenshots 目录。
     */
    fun isScreenshotEntry(displayName: String?, pathOrDir: String?): Boolean {
        val n = (displayName ?: "").lowercase()
        val p = (pathOrDir ?: "").lowercase()
        if (n.startsWith("screenshot")) return true
        if (n.startsWith("screen shot")) return true
        if (n.startsWith("screen_capture") || n.startsWith("screencapture")) return true
        if (n.startsWith("截屏") || n.startsWith("截图")) return true
        if (p.contains("screenshot")) return true   // 含 Pictures/Screenshots 目录
        if (p.contains("screen_capture") || p.contains("screencapture")) return true
        return false
    }

    /** Uri → Bitmap，采样解码防 OOM（长边压到 maxSide 以内） */
    suspend fun decodeSampled(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()
        }

    /** 默认时间窗：10 分钟。截屏后隔多久点球还认。 */
    const val DEFAULT_WINDOW_MS = 10 * 60 * 1000L
}

package com.killbill.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper

/**
 * MediaProjection 会话持有者。
 *
 * Android 14 关键点：
 * - getMediaProjection 的授权结果只能创建一个会话，会话 onStop 后必须重新走授权（R1 缓解）。
 * - 创建 VirtualDisplay 前必须已注册 Callback（Android 14 硬性要求，否则 SecurityException）。
 * - 使用 projection 期间必须有 mediaProjection 类型的前台服务在跑（由 FloatingBallService 负责升降级类型）。
 */
object ProjectionHolder {

    @Volatile
    var projection: MediaProjection? = null
        private set

    private var resultCode: Int = 0
    private var resultData: Intent? = null

    private var pendingCallback: ((Boolean) -> Unit)? = null

    /** 取当前可用会话；无授权/已失效返回 null */
    fun get(ctx: Context): MediaProjection? {
        projection?.let { return it }
        val data = resultData ?: return null
        return try {
            val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
            mgr.getMediaProjection(resultCode, data).also { p ->
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        // 会话失效：清干净，下次重新授权
                        projection = null
                        resultData = null
                    }
                }, Handler(Looper.getMainLooper()))
                projection = p
            }
        } catch (e: Exception) {
            resultData = null
            null
        }
    }

    /** 拉起透明 Activity 请求截屏授权，结果回调（主线程） */
    fun requestPermission(ctx: Context, onResult: (Boolean) -> Unit) {
        pendingCallback = onResult
        try {
            ctx.startActivity(
                Intent(ctx, CapturePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            pendingCallback = null
            onResult(false)
        }
    }

    /** CapturePermissionActivity 回传授权结果 */
    fun onPermissionResult(code: Int, data: Intent?) {
        val cb = pendingCallback
        pendingCallback = null
        if (code == Activity.RESULT_OK && data != null) {
            resultCode = code
            resultData = data
            cb?.invoke(true)
        } else {
            cb?.invoke(false)
        }
    }

    fun release() {
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        resultData = null
    }
}

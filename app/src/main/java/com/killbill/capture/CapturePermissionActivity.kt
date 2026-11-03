package com.killbill.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 透明授权页：唯一职责是拉起系统 MediaProjection 授权对话框并把结果回传 ProjectionHolder。
 * 无 UI、秒开秒关。
 */
class CapturePermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ProjectionHolder.onPermissionResult(result.resultCode, result.data)
        finish()
    }

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // 进程重建场景：直接取消，让用户重点一次悬浮球
            ProjectionHolder.onPermissionResult(RESULT_CANCELED, Intent())
            finish()
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        launcher.launch(mgr.createScreenCaptureIntent())
        launched = true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!launched && !isFinishing) {
            ProjectionHolder.onPermissionResult(RESULT_CANCELED, Intent())
        }
    }
}

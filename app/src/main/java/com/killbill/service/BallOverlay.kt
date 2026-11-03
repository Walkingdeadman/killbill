package com.killbill.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import com.killbill.R
import kotlin.math.abs

/**
 * 悬浮球：拖动 + 松手贴边 + 点击触发截屏。
 * 触摸区分拖动/点击用 touchSlop 阈值。
 */
class BallOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
) {
    val view: View = LayoutInflater.from(context).inflate(R.layout.overlay_ball, null)
    private val icon: ImageView = view.findViewById(R.id.ballIcon)
    private val loading: ProgressBar = view.findViewById(R.id.ballLoading)

    private val density = context.resources.displayMetrics.density
    private val size = (56 * density).toInt()
    private val touchSlop = (10 * density)

    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private var attached = false
    private var snapAnimator: ValueAnimator? = null

    companion object {
        /** 静息半透明，不挡内容；按下恢复全亮 */
        private const val IDLE_ALPHA = 0.88f
    }

    private val screenW: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.let { d ->
                val m = android.graphics.Point()
                d.getRealSize(m)
                m.x
            }
        }

    private val screenH: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.let { d ->
                val m = android.graphics.Point()
                d.getRealSize(m)
                m.y
            }
        }

    init {
        view.setOnTouchListener(DragTouchListener())
        view.alpha = IDLE_ALPHA
    }

    fun attach() {
        if (attached) return
        params.x = screenW - size - (8 * density).toInt()
        params.y = (screenH * 0.4f).toInt()
        wm.addView(view, params)
        attached = true
    }

    /** 点击弹性动画：按下缩 → 弹回 */
    private fun bounce() {
        view.animate().cancel()
        view.animate()
            .scaleX(0.8f).scaleY(0.8f)
            .setDuration(70)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    /** 感知到用户系统截屏：双脉冲提示"我看到了，点我" */
    fun pulse() {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(130).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(130).withEndAction {
                view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
            }.start()
        }.start()
    }

    fun detach() {
        snapAnimator?.cancel()
        if (attached) {
            runCatching { wm.removeViewImmediate(view) }
            attached = false
        }
    }

    fun hide() {
        view.visibility = View.INVISIBLE
    }

    fun show() {
        view.visibility = View.VISIBLE
    }

    fun setLoading(b: Boolean) {
        loading.visibility = if (b) View.VISIBLE else View.GONE
        icon.alpha = if (b) 0.55f else 1f
    }

    private fun snapToEdge() {
        snapAnimator?.cancel()
        val startX = params.x
        val targetX = if (startX + size / 2 < screenW / 2) 0 else screenW - size
        if (startX == targetX) return
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (attached) runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var dragging = false
        private var lastEventTime = 0L

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    view.alpha = 1f
                    downRawX = e.rawX
                    downRawY = e.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    lastEventTime = e.eventTime
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (downX + dx).toInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
                        params.y = (downY + dy).toInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
                        if (attached) runCatching { wm.updateViewLayout(view, params) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    view.alpha = IDLE_ALPHA
                    if (dragging) {
                        snapToEdge()
                    } else {
                        // 防抖：600ms 内的重复点击忽略（识别中 busy 由 Service 层再挡一道）
                        val now = System.currentTimeMillis()
                        if (now - lastEventTime > 600) {
                            bounce()
                            onTap()
                        }
                        lastEventTime = now
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.alpha = IDLE_ALPHA
                    if (dragging) snapToEdge()
                    return true
                }
            }
            return false
        }
    }
}

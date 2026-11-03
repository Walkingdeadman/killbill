package com.killbill.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.killbill.KillBillApp
import com.killbill.MainActivity
import com.killbill.R
import com.killbill.ai.BillDraft
import com.killbill.ai.OcrExtractor
import com.killbill.ai.QwenVisionClient
import com.killbill.ai.TextBillParser
import com.killbill.capture.ProjectionHolder
import com.killbill.capture.ScreenCapturer
import com.killbill.capture.ScreenshotFinder
import com.killbill.util.DiagLog
import com.killbill.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 常驻前台服务：悬浮球 + 截屏 + AI 识别 + 确认卡片全链路编排。
 *
 * 前台服务类型策略（Android 14 兼容，R1）：
 * - 常驻时用 specialUse（此时还没有截屏授权，mediaProjection 类型会炸 SecurityException）
 * - 用户授权截屏后、capture 前升级为 specialUse|mediaProjection，capture 完降级回去
 */
class FloatingBallService : Service() {

    companion object {
        const val ACTION_START = "com.killbill.action.START"
        const val ACTION_STOP = "com.killbill.action.STOP"
        private const val NOTIF_ID = 1001
        /** 隐藏 overlay 到实际截屏的等待，防止悬浮球被截进去（R7） */
        private const val OVERLAY_HIDE_DELAY_MS = 200L

        fun start(ctx: Context) {
            val i = Intent(ctx, FloatingBallService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingBallService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as KillBillApp

    private var ball: BallOverlay? = null
    private var card: ConfirmCardOverlay? = null
    private var busy = false
    private var lastPulseAt = 0L
    private var analyzeJob: kotlinx.coroutines.Job? = null
    private var qwenClient: QwenVisionClient? = null
    private var userCancelled = false
    private var lastAutoUri: String? = null
    private var ocrBrokenToasted = false

    private val wm: WindowManager by lazy { getSystemService(WindowManager::class.java) }

    /** 用户系统截屏落相册 → 悬浮球弹一下："我看到了，点我" */
    private val mediaObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            onMediaMaybeChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundTyped(mediaProjection = false)
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
        }
    }

    private fun onMediaMaybeChanged() {
        val now = System.currentTimeMillis()
        if (now - lastPulseAt < 4000) return // 防抖：MediaStore 一次写入会连发多个 onChange
        scope.launch {
            if (!ScreenshotFinder.hasReadPermission(this@FloatingBallService)) return@launch
            val shot = ScreenshotFinder.findLatest(this@FloatingBallService, 60_000) ?: return@launch
            lastPulseAt = System.currentTimeMillis()
            ball?.pulse()

            // 零操作模式：截屏即识别。本地 OCR+规则，毫秒级零网络；
            // 只认置信度高的账单，其余静默放过（不自动送模型，不让用户莫名排队两分钟）
            if (!app.prefs.autoAnalyzeScreenshot) return@launch
            if (busy || card != null) return@launch
            if (shot.uri.toString() == lastAutoUri) return@launch
            lastAutoUri = shot.uri.toString()
            DiagLog.log("auto", "截屏感知: ${shot.name}")
            busy = true
            ball?.setLoading(true)
            try {
                val bmp = ScreenshotFinder.decodeSampled(this@FloatingBallService, shot.uri)
                if (bmp == null) {
                    DiagLog.log("auto", "截屏解码失败")
                    return@launch
                }
                val lines = OcrExtractor.recognize(this@FloatingBallService, bmp)
                bmp.recycle()
                DiagLog.log("auto", "OCR lines=${lines?.size ?: "null"} broken=${OcrExtractor.broken} err=${OcrExtractor.lastError}")
                if (lines == null && OcrExtractor.broken && !ocrBrokenToasted) {
                    ocrBrokenToasted = true
                    toast("本地 OCR 引擎无响应已停用，自动识别暂停；点球手动流程不受影响")
                }
                val local = lines?.let { TextBillParser.parse(it) }
                DiagLog.log("auto", "规则解析: ${if (local == null) "null" else "confident=${local.confident} amount=${local.draft.amountCents}"}")
                if (local != null && local.confident) {
                    showCard(local.draft, "截屏自动识别 · ${shot.name}")
                    toast("截到你刚截的账单了，核对一下")
                }
                // 不置信：安静等用户点球走完整链路，不弹窗不送模型
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.log("auto", "异常: ${e.javaClass.simpleName} ${e.message}")
                // 自动链路出错绝不打扰用户，点球的手动链路还在
            } finally {
                busy = false
                ball?.setLoading(false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanupAndStop()
                return START_NOT_STICKY
            }

            else -> showBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(mediaObserver) }
        card?.dismiss()
        card = null
        ball?.detach()
        ball = null
        ProjectionHolder.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 悬浮球 ----------

    private fun showBall() {
        if (ball == null) {
            ball = BallOverlay(this, wm, onTap = ::onBallClick).also { it.attach() }
        }
    }

    private fun onBallClick() {
        if (busy) {
            // 识别中再点一次 = 取消。网关慢成狗，控制权必须在用户手里。
            userCancelled = true
            analyzeJob?.cancel()
            qwenClient?.cancelAll()
            toast("已取消识别")
            return
        }
        if (card != null) return
        if (app.prefs.apiKey.isBlank()) {
            toast("还没配 API Key，去 App 设置页填一下")
            openMain()
            return
        }
        busy = true
        userCancelled = false
        ball?.setLoading(true)
        ball?.hide()
        val client = QwenVisionClient(app.prefs).also { qwenClient = it }
        analyzeJob = scope.launch {
            // 通知栏报秒计时器：网关一两分钟才回话，不能让用户以为 App 死了
            val ticker = scope.launch {
                var waited = 0
                while (true) {
                    delay(15_000)
                    waited += 15
                    updateNotification("AI 识别中…已等待 ${waited}s（网关慢，再点悬浮球取消）")
                }
            }
            var bmp: Bitmap? = null
            try {
                var srcMeta: String? = null

                // 路径一（主）：从相册捞最近的系统截屏——无授权弹框、无录制指示
                if (ScreenshotFinder.hasReadPermission(this@FloatingBallService)) {
                    val shot = ScreenshotFinder.findLatest(this@FloatingBallService)
                    if (shot != null) {
                        bmp = ScreenshotFinder.decodeSampled(this@FloatingBallService, shot.uri)
                        if (bmp != null) {
                            srcMeta = "相册截屏 · ${shot.name}"
                            toast("已取到你刚才的截屏，AI 识别中…")
                        }
                    }
                } else {
                    toast("没给相册权限，走直截屏（去设置页授权可免弹框）")
                }

                // 路径二（降级）：最近 10 分钟没有截屏 → MediaProjection 直截当前屏
                if (bmp == null) {
                    bmp = doCapture()
                    if (bmp == null) {
                        toast("没拿到图（无近期截屏且直截屏授权被拒）")
                        return@launch
                    }
                    srcMeta = "实时截屏"
                    toast("已截屏，AI 识别中…")
                }

                // ---- 三级识别链路 ----
                var draft: BillDraft? = null
                var via: String? = null
                var lastErr: String? = null
                DiagLog.log("click", "取图完成 src=$srcMeta")

                // A：本地 OCR + 规则秒解析，零网络（标准账单页毫秒出卡）
                val lines = OcrExtractor.recognize(this@FloatingBallService, bmp)
                DiagLog.log("ocr", "lines=${lines?.size ?: "null"} broken=${OcrExtractor.broken} err=${OcrExtractor.lastError}")
                if (!lines.isNullOrEmpty()) {
                    val local = TextBillParser.parse(lines)
                    DiagLog.log("rule", if (local == null) "null" else "confident=${local.confident} amount=${local.draft.amountCents} merchant=${local.draft.merchant}")
                    if (local != null && local.confident) {
                        draft = local.draft
                        via = "本地OCR秒解析"
                    }
                }

                // B：OCR 有字但规则没把握 → 文本送模型（token 约为全图零头）
                if (draft == null && !lines.isNullOrEmpty()) {
                    DiagLog.log("api", "走文本模型兜底")
                    when (val r = client.analyzeText(lines.joinToString("\n"))) {
                        is QwenVisionClient.Result.Ok -> {
                            draft = r.draft
                            via = "OCR文本+模型"
                            DiagLog.log("api", "文本模型 OK amount=${r.draft.amountCents}")
                        }
                        is QwenVisionClient.Result.NotBill ->
                            toast("模型判定这不是账单，没记")
                        is QwenVisionClient.Result.Error -> {
                            lastErr = r.msg
                            DiagLog.log("api", "文本模型失败: ${r.msg}")
                        }
                    }
                }

                // C：OCR 引擎不可用/坏死 → 老路全图送模型
                if (draft == null && lines == null) {
                    DiagLog.log("api", "走全图模型")
                    val base64 = ScreenCapturer.encodeJpeg(bmp)
                    when (val r = client.analyze(base64)) {
                        is QwenVisionClient.Result.Ok -> {
                            draft = r.draft
                            via = "全图模型"
                            DiagLog.log("api", "全图模型 OK amount=${r.draft.amountCents}")
                        }
                        is QwenVisionClient.Result.NotBill ->
                            toast("模型判定这不是账单，没记（误判的话检查设置里的模型名）")
                        is QwenVisionClient.Result.Error -> {
                            lastErr = r.msg
                            DiagLog.log("api", "全图模型失败: ${r.msg}")
                        }
                    }
                }
                bmp.recycle()
                bmp = null

                when {
                    draft != null -> {
                        DiagLog.log("card", "弹卡 via=$via amount=${draft.amountCents}")
                        showCard(draft, listOfNotNull(srcMeta, via).joinToString(" · "))
                    }
                    lastErr != null -> {
                        DiagLog.log("card", "弹手动卡 err=$lastErr")
                        showCard(BillDraft.manual("识别失败: $lastErr"), srcMeta)
                    }
                    // NotBill 已经 toast 过，静默收场
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动取消，安静退场，不弹卡片
            } catch (e: Exception) {
                if (!userCancelled) {
                    showCard(BillDraft.manual("出错了: ${e.javaClass.simpleName} ${e.message}"))
                }
            } finally {
                ticker.cancel()
                bmp?.takeIf { !it.isRecycled }?.recycle()
                busy = false
                userCancelled = false
                qwenClient = null
                ball?.setLoading(false)
                ball?.show()
                updateNotification(null)
            }
        }
    }

    /** 前台服务通知文案更新：extra=null 恢复默认文案 */
    private fun updateNotification(extra: String?) {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(extra))
        }
    }

    // ---------- 截屏 ----------

    private suspend fun doCapture(): Bitmap? {
        if (ProjectionHolder.projection == null) {
            val granted = suspendCancellableCoroutine<Boolean> { cont ->
                ProjectionHolder.requestPermission(this@FloatingBallService) { g ->
                    if (cont.isActive) cont.resume(g)
                }
            }
            if (!granted) return null
        }
        // Android 14：projection 会话期间前台服务类型必须包含 mediaProjection
        startForegroundTyped(mediaProjection = true)
        return try {
            delay(OVERLAY_HIDE_DELAY_MS) // 等授权页消失 + 悬浮球隐藏（R7）
            val p = ProjectionHolder.get(this) ?: return null
            ScreenCapturer.capture(p, this)
        } finally {
            if (!app.prefs.keepProjectionSession) {
                // 一次一截：截完立刻释放会话，系统"正在录制"指示随用随灭。
                // 代价：Android 14+ 下次截屏重新弹授权（系统强制，无解）。
                ProjectionHolder.release()
            }
            startForegroundTyped(mediaProjection = false)
        }
    }

    private fun startForegroundTyped(mediaProjection: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (mediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else 0
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        }
    }

    private fun buildNotification(extra: String? = null): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, KillBillApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(extra ?: "悬浮球运行中，点屏幕上的球记一笔")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ---------- 确认卡片 ----------

    private fun showCard(draft: BillDraft, srcMeta: String? = null) {
        if (card != null) return
        val c = ConfirmCardOverlay(this, wm, app, draft, srcMeta)
        c.onConfirm = { result ->
            scope.launch {
                val id = app.billRepo.insertManual(
                    amountCents = result.amountCents,
                    type = result.type,
                    categoryId = result.categoryId,
                    merchant = result.merchant,
                    note = result.note,
                    txTime = result.txTime ?: System.currentTimeMillis(),
                )
                if (id > 0) toast("已记一笔 ¥${Money.yuan(result.amountCents)}")
                else toast("入账失败，去 App 里手动记")
            }
        }
        c.onDone = { card = null }
        card = c
        c.show()
    }

    // ---------- 杂项 ----------

    private fun toast(msg: String) {
        scope.launch { Toast.makeText(this@FloatingBallService, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
        )
    }

    private fun cleanupAndStop() {
        card?.dismiss()
        card = null
        ball?.detach()
        ball = null
        ProjectionHolder.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

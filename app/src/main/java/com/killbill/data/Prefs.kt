package com.killbill.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 存储层。首选 EncryptedSharedPreferences（Keystore 加密）。
 *
 * 防御性降级：部分 ROM（ColorOS 等）的 Android Keystore 有兼容性问题，
 * EncryptedSharedPreferences.create 会直接抛异常导致启动闪退。
 * 这里 catch 住降级为普通 SharedPreferences，API Key 用 Base64 混淆存储
 * （安全性降级但可用，总比闪退强）。
 */
class Prefs(ctx: Context) {

    private val appCtx = ctx.applicationContext

    private val encrypted: Boolean
    private val sp: SharedPreferences

    init {
        var tmp: SharedPreferences? = null
        var ok = false
        try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            tmp = EncryptedSharedPreferences.create(
                appCtx,
                "killbill_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // 有些 ROM create 不炸、第一次读才炸，这里主动探一次雷
            tmp.getString("probe", null)
            ok = true
        } catch (t: Throwable) {
            ok = false
            tmp = null
        }
        encrypted = ok
        sp = tmp ?: appCtx.getSharedPreferences("killbill_prefs_fallback", Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = decode(sp.getString(KEY_API, ""))
        set(v) = sp.edit().putString(KEY_API, encode(v.trim())).apply()

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString(KEY_BASE_URL, v.trim()).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString(KEY_MODEL, v.trim()).apply()

    var ballEnabled: Boolean
        get() = sp.getBoolean(KEY_BALL, true)
        set(v) = sp.edit().putBoolean(KEY_BALL, v).apply()

    /**
     * 截屏会话保持。
     * false（默认）：一次一截，截完立刻释放 MediaProjection 会话，系统录制指示随用随灭；
     *   代价是 Android 14+ 每次截屏都重新弹系统授权框（谷歌强制，绕不过）。
     * true：会话常驻，免重复授权，但状态栏录制/投射指示会一直亮着。
     */
    var keepProjectionSession: Boolean
        get() = sp.getBoolean(KEY_KEEP_SESSION, false)
        set(v) = sp.edit().putBoolean(KEY_KEEP_SESSION, v).apply()

    /**
     * 截屏自动识别：系统截屏一落相册就自动跑本地 OCR+规则，
     * 是账单且置信度高直接弹确认卡片（零网络、毫秒级）。
     * 规则没把握的不会自动送模型（不想让用户莫名等两分钟），点球才走完整链路。
     */
    var autoAnalyzeScreenshot: Boolean
        get() = sp.getBoolean(KEY_AUTO_ANALYZE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_ANALYZE, v).apply()

    /** 降级模式下 Key 做 Base64 混淆；加密模式下明文（反正文件本身是加密的） */
    private fun encode(raw: String): String =
        if (encrypted) raw
        else Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)

    private fun decode(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (encrypted) return stored
        return try {
            String(Base64.decode(stored, Base64.NO_WRAP))
        } catch (e: Exception) {
            stored // 兼容：解不开就当明文用
        }
    }

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_BALL = "ball_enabled"
        private const val KEY_KEEP_SESSION = "keep_projection_session"
        private const val KEY_AUTO_ANALYZE = "auto_analyze_screenshot"
        const val DEFAULT_BASE_URL = "https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1"
        // 实测 flash 与 max 在该网关延迟相同（~2min/请求，网关队列问题），精度 4/4 对账全对，flash 省 token
        const val DEFAULT_MODEL = "qwen3.8-flash"
    }
}

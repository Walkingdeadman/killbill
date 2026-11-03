package com.killbill.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：真机出问题用户说不清，老子也看不见，全链路埋点写文件。
 * 路径：/sdcard/Android/data/com.killbill/files/killbill_diag.log（adb 可直接 cat）
 * 设置页也有查看入口。超 512KB 自动清。
 */
object DiagLog {

    private var file: File? = null

    fun init(ctx: Context) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        file = File(dir, "killbill_diag.log")
    }

    fun log(stage: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            f.appendText("${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())} [$stage] $msg\n")
        }
    }

    fun path(): String = file?.absolutePath ?: "(未初始化)"

    fun tail(n: Int = 40): String = runCatching {
        file?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: "(日志为空)"
    }.getOrDefault("(读日志失败)")
}

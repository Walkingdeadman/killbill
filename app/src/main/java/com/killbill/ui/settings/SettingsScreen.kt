package com.killbill.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.killbill.KillBillApp
import com.killbill.ai.OcrExtractor
import com.killbill.ai.QwenVisionClient
import com.killbill.capture.ScreenshotFinder
import com.killbill.data.Prefs
import com.killbill.util.DiagLog
import com.killbill.service.FloatingBallService
import com.killbill.ui.theme.ExpenseRed
import com.killbill.ui.theme.IncomeGreen
import com.killbill.ui.theme.TextGray
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as KillBillApp
    val prefs: Prefs = app.prefs
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    var model by remember { mutableStateOf(prefs.model) }
    var advancedOpen by remember { mutableStateOf(false) }

    var testState by remember { mutableStateOf<String?>(null) } // null=空闲
    var testing by remember { mutableStateOf(false) }

    // 权限状态，onResume 刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    var permVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) permVersion++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val overlayGranted = remember(permVersion) { AndroidSettings.canDrawOverlays(ctx) }
    val notifGranted = remember(permVersion) {
        if (Build.VERSION.SDK_INT >= 33)
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
    }
    var ballEnabled by remember(permVersion) { mutableStateOf(prefs.ballEnabled && overlayGranted) }

    // 相册权限运行时申请
    val mediaPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permVersion++ }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // ---- API Key ----
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("千问 API Key", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "默认走千问 Token Plan 接口，Key 加密存储在本机",
                    fontSize = 12.sp, color = TextGray
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testState = null },
                    label = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        Text(
                            if (showKey) "隐藏" else "显示",
                            fontSize = 12.sp,
                            color = TextGray,
                            modifier = Modifier.padding(end = 8.dp)
                                .clickable { showKey = !showKey }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            prefs.apiKey = apiKey
                            testState = "已保存"
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                    OutlinedButton(
                        onClick = {
                            prefs.apiKey = apiKey
                            testing = true
                            testState = null
                            scope.launch {
                                val (ok, msg) = QwenVisionClient(prefs).testKey()
                                testing = false
                                testState = (if (ok) "✓ " else "✗ ") + msg
                            }
                        },
                        enabled = !testing && apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(" 测试中", modifier = Modifier.padding(start = 6.dp))
                        } else Text("测试连接")
                    }
                }
                testState?.let {
                    Text(
                        it, fontSize = 13.sp,
                        color = if (it.startsWith("✓")) IncomeGreen else if (it.startsWith("✗")) ExpenseRed else TextGray
                    )
                }
                Text(
                    if (advancedOpen) "收起高级设置 ▲" else "高级设置（接口/模型）▼",
                    fontSize = 13.sp,
                    color = TextGray,
                    modifier = Modifier.clickable { advancedOpen = !advancedOpen }
                )
                if (advancedOpen) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        prefs.baseUrl = baseUrl.ifBlank { Prefs.DEFAULT_BASE_URL }
                        prefs.model = model.ifBlank { Prefs.DEFAULT_MODEL }
                        testState = "接口配置已保存"
                    }) { Text("保存接口配置") }
                }
            }
        }

        // ---- 悬浮球 ----
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Column {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("悬浮球", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "开启后飘在屏幕上，点一下截屏记账",
                            fontSize = 12.sp, color = TextGray
                        )
                    }
                    Switch(
                        checked = ballEnabled,
                        onCheckedChange = { on ->
                            if (on) {
                                if (!overlayGranted) {
                                    // 去系统授权悬浮窗
                                    ctx.startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${ctx.packageName}")
                                        )
                                    )
                                    return@Switch
                                }
                                prefs.ballEnabled = true
                                ballEnabled = true
                                FloatingBallService.start(ctx)
                            } else {
                                prefs.ballEnabled = false
                                ballEnabled = false
                                FloatingBallService.stop(ctx)
                            }
                        }
                    )
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                var autoAnalyze by remember { mutableStateOf(prefs.autoAnalyzeScreenshot) }
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("截屏自动识别", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "系统截屏后自动本地识别，是账单直接弹确认卡片，连球都不用点。只走本地 OCR+规则，不过网关",
                            fontSize = 12.sp, color = TextGray
                        )
                    }
                    Switch(
                        checked = autoAnalyze,
                        onCheckedChange = {
                            autoAnalyze = it
                            prefs.autoAnalyzeScreenshot = it
                        }
                    )
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                var keepSession by remember { mutableStateOf(prefs.keepProjectionSession) }
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("截屏会话保持", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "关（默认）：一次一截，系统\"正在录制\"指示截完即灭，但每次截屏要重新弹系统授权框（Android 14+ 强制）\n开：免重复授权，代价是录制指示常亮",
                            fontSize = 12.sp, color = TextGray
                        )
                    }
                    Switch(
                        checked = keepSession,
                        onCheckedChange = {
                            keepSession = it
                            prefs.keepProjectionSession = it
                        }
                    )
                }
            }
        }

        // ---- 权限自检 ----
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权限自检", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                PermRow("悬浮窗（显示在其他应用上层）", overlayGranted) {
                    ctx.startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
                PermRow("通知（前台服务保活）", notifGranted) {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }
                val mediaGranted = remember(permVersion) { ScreenshotFinder.hasReadPermission(ctx) }
                PermRow("相册图片（读取你的截屏，主流程必需）", mediaGranted) {
                    mediaPermLauncher.launch(ScreenshotFinder.requiredPermission())
                }
                var ocrState by remember { mutableStateOf("检测中…") }
                LaunchedEffect(Unit) {
                    ocrState = if (OcrExtractor.probe(ctx))
                        "OCR 引擎：可用（标准账单页本地秒解析，不过网关）"
                    else
                        "OCR 引擎：不可用（缺 Google Play 服务，识别走网关较慢）"
                }
                Text(ocrState, fontSize = 12.sp, color = TextGray)
                Text(
                    "截屏授权：第一次点悬浮球时系统会弹框，同意即可。Android 14 上会话失效后会重新弹，属正常。",
                    fontSize = 12.sp, color = TextGray
                )
                Text(
                    "国产 ROM 请把本 App 加入电池白名单/允许后台运行，否则悬浮球可能被杀。",
                    fontSize = 12.sp, color = TextGray
                )
                var showDiag by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showDiag = true }) { Text("查看诊断日志（出问题发给开发者）") }
                Text(DiagLog.path(), fontSize = 11.sp, color = TextGray)
                if (showDiag) {
                    var content by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) { content = DiagLog.tail(60) }
                    AlertDialog(
                        onDismissRequest = { showDiag = false },
                        title = { Text("诊断日志（最近 60 行）") },
                        text = {
                            Text(
                                content,
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = { TextButton(onClick = { showDiag = false }) { Text("关闭") } }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (granted) "✓" else "✗",
            color = if (granted) IncomeGreen else ExpenseRed,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label, fontSize = 14.sp,
            modifier = Modifier.padding(start = 10.dp).weight(1f)
        )
        if (!granted) {
            OutlinedButton(onClick = onFix) { Text("去授权", fontSize = 13.sp) }
        }
    }
}

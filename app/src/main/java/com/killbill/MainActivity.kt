package com.killbill

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.killbill.service.FloatingBallService
import com.killbill.ui.AppViewModel
import com.killbill.ui.categories.CategoriesScreen
import com.killbill.ui.settings.SettingsScreen
import com.killbill.ui.stats.StatsScreen
import com.killbill.ui.theme.KillBillTheme
import com.killbill.ui.transactions.TransactionsScreen
import android.provider.Settings as AndroidSettings

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TAB = "extra_tab"
        const val TAB_STATS = 0
        const val TAB_TRANSACTIONS = 1
        const val TAB_CATEGORIES = 2
        const val TAB_SETTINGS = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startTab = intent?.getIntExtra(EXTRA_TAB, TAB_STATS) ?: TAB_STATS
        setContent {
            // activity-compose 1.9 + Compose 1.6 组合下 setContent 不自动提供
            // LocalLifecycleOwner，collectAsStateWithLifecycle 会当场炸（启动闪退根因）。
            // 自己显式提供，谁也别指望。
            CompositionLocalProvider(LocalLifecycleOwner provides this@MainActivity) {
                KillBillTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppRoot(startTab)
                    }
                }
            }
        }
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
private fun AppRoot(startTab: Int) {
    val vm: AppViewModel = viewModel()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(startTab) }

    val tabs = listOf(
        TabItem("统计", Icons.Filled.PieChart),
        TabItem("明细", Icons.Filled.ReceiptLong),
        TabItem("类别", Icons.Filled.Category),
        TabItem("设置", Icons.Filled.Settings),
    )

    // 通知权限（Android 13+）
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    // 相册权限（读截屏，主取图路径）
    val mediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        // 启动路径全兜底：任何 ROM 兼容性问题都不许把 App 干崩
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        runCatching {
            val perm = com.killbill.capture.ScreenshotFinder.requiredPermission()
            if (ctx.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                mediaLauncher.launch(perm)
            }
        }
        runCatching {
            // 悬浮球自动恢复：开关开着 + 悬浮窗权限在 → 拉起服务（AC13）
            val prefs = (ctx.applicationContext as KillBillApp).prefs
            if (prefs.ballEnabled && AndroidSettings.canDrawOverlays(ctx)) {
                FloatingBallService.start(ctx)
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { inner ->
        when (tab) {
            MainActivity.TAB_STATS -> StatsScreen(vm, Modifier.padding(inner))
            MainActivity.TAB_TRANSACTIONS -> TransactionsScreen(vm, Modifier.padding(inner))
            MainActivity.TAB_CATEGORIES -> CategoriesScreen(vm, Modifier.padding(inner))
            MainActivity.TAB_SETTINGS -> SettingsScreen(Modifier.padding(inner))
        }
    }
}

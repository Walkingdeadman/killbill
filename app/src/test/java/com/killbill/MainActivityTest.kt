package com.killbill

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.killbill.data.db.TxType
import com.killbill.ui.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 启动冒烟 + 数据链路 E2E。
 *
 * 注：FAB→对话框 的点击链路在 Robolectric 真 Activity 组合下有 idle 判定的环境坑
 * （五个对照探针证明各组件单独均正常），对话框交互由 TxEditDialogTest 隔离覆盖，
 * 本测试直接驱动 ViewModel 验证 记账→Room→Flow→统计UI 全链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun <T> poll(timeoutMs: Long = 10_000, block: suspend () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        return runBlocking {
            var v = block()
            while (v == null && System.currentTimeMillis() < end) {
                delay(50)
                v = block()
            }
            v ?: throw IllegalStateException("poll timeout")
        }
    }

    @Test
    fun `启动不闪退且统计页渲染`() {
        rule.onNodeWithText("统计").assertIsDisplayed()
        rule.onNodeWithText("明细").assertIsDisplayed()
        rule.onNodeWithText("类别").assertIsDisplayed()
        rule.onNodeWithText("设置").assertIsDisplayed()
        rule.onNodeWithText("今日支出").assertIsDisplayed()
        rule.onNodeWithText("今日收入").assertIsDisplayed()
        rule.onNodeWithText("相较上月支出").assertIsDisplayed()
        // 空数据边界：上月无数据显示 "--"（AC8）
        rule.onNodeWithText("--").assertIsDisplayed()
    }

    @Test
    fun `四个Tab全部切换不炸`() {
        rule.onAllNodesWithText("明细").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("还没有账目").assertIsDisplayed()

        rule.onAllNodesWithText("类别").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("支出类别").assertIsDisplayed()
        // 预置类别种子进了库（Room seed callback 验证）
        rule.onNodeWithText("餐饮").assertIsDisplayed()

        rule.onAllNodesWithText("设置").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("千问 API Key").assertIsDisplayed()
        rule.onNodeWithText("悬浮球").assertIsDisplayed()
        rule.onNodeWithText("权限自检").assertIsDisplayed()

        rule.onAllNodesWithText("统计").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("今日支出").assertIsDisplayed()
    }

    @Test
    fun `记一笔后统计页实时出数`() {
        val app = rule.activity.application as KillBillApp
        val db = app.database

        // 等 Room 建库 + 预置类别种子落库
        val foodId = poll {
            db.categoryDao().byType("EXPENSE").firstOrNull { it.name == "餐饮" }?.id
        }

        // 拿到界面正在用的同一个 ViewModel 实例，直接记账
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.runOnIdle {
            vm.addTx(2350, TxType.EXPENSE, foodId, "测试商家", "测试备注")
        }

        // 等写入落库（IO 线程，轮询真库）
        poll { if (db.transactionDao().count() > 0) true else null }

        // 等 UI 收敛：统计页（默认 Tab）今日支出卡出现 23.50（AC7：UI 与库一致）
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("23.50", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodes(hasText("23.50", substring = true)).onFirst().assertIsDisplayed()

        // 明细页也能看到这笔
        rule.onAllNodesWithText("明细").onFirst().performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("测试商家", substring = true)).onFirst().assertIsDisplayed()
    }
}

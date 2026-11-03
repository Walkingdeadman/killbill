package com.killbill.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TransactionEntity
import com.killbill.data.db.TxType
import com.killbill.ui.transactions.TxEditDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TxEditDialog 隔离测试：填金额、选类别、切收支、保存回调全链路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TxEditDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val cats = listOf(
        CategoryEntity(1, "餐饮", "EXPENSE", 0xFFE57373L),
        CategoryEntity(10, "其他", "EXPENSE", 0xFFBDBDBDL),
        CategoryEntity(11, "工资", "INCOME", 0xFF81C784L),
        CategoryEntity(15, "其他", "INCOME", 0xFFBDBDBDL),
    )

    @Test
    fun `新增模式_填金额保存_金额分毫不差`() {
        var saved: TransactionEntity? = null
        rule.setContent {
            TxEditDialog(initial = null, categories = cats, onSave = { saved = it }, onDismiss = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("手动记一笔").assertIsDisplayed()

        // 保存按钮初始禁用（金额为空）
        rule.onNodeWithText("保存").assertIsDisplayed()

        rule.onNodeWithText("金额（元）").performTextInput("23.50")
        rule.waitForIdle()
        rule.onNodeWithText("保存").performClick()
        rule.waitForIdle()

        val tx = saved
        assertNotNull("保存回调必须触发", tx)
        assertEquals(2350L, tx!!.amountCents)
        assertEquals(TxType.EXPENSE.name, tx.type)
        assertEquals(10L, tx.categoryId) // 默认归"其他"（支出）
        assertEquals("MANUAL", tx.source)
    }

    @Test
    fun `选类别后保存_类别跟随`() {
        var saved: TransactionEntity? = null
        rule.setContent {
            TxEditDialog(initial = null, categories = cats, onSave = { saved = it }, onDismiss = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("金额（元）").performTextInput("66")
        rule.onNodeWithText("餐饮").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("保存").performClick()
        rule.waitForIdle()
        assertEquals(1L, saved?.categoryId)
        assertEquals(6600L, saved?.amountCents)
    }

    @Test
    fun `切到收入_类别集合跟着切`() {
        var saved: TransactionEntity? = null
        rule.setContent {
            TxEditDialog(initial = null, categories = cats, onSave = { saved = it }, onDismiss = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("收入").performClick()
        rule.waitForIdle()
        // 收入类别出现
        rule.onNodeWithText("工资").assertIsDisplayed()
        rule.onNodeWithText("金额（元）").performTextInput("8888.88")
        rule.onNodeWithText("工资").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("保存").performClick()
        rule.waitForIdle()
        assertEquals(TxType.INCOME.name, saved?.type)
        assertEquals(11L, saved?.categoryId)
        assertEquals(888888L, saved?.amountCents)
    }

    @Test
    fun `编辑模式_预填原值`() {
        var saved: TransactionEntity? = null
        val existing = TransactionEntity(
            id = 42, amountCents = 12345, type = "EXPENSE", categoryId = 1,
            merchant = "老商家", note = "旧备注", txTime = 1000L, source = "CAPTURE"
        )
        rule.setContent {
            TxEditDialog(initial = existing, categories = cats, onSave = { saved = it }, onDismiss = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("编辑账目").assertIsDisplayed()
        // 不改金额直接保存 → 原值保留
        rule.onNodeWithText("保存").performClick()
        rule.waitForIdle()
        assertEquals(42L, saved?.id)
        assertEquals(12345L, saved?.amountCents)
        assertEquals("老商家", saved?.merchant)
        assertTrue(saved?.amountCents == 12345L)
    }
}

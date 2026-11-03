package com.killbill.ui.transactions

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TxType
import com.killbill.ui.theme.KillBillTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 批量导入确认页隔离测试：识别结果展示、勾选、编辑入口和确认按钮可用性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchImportScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val cats = listOf(
        CategoryEntity(1, "交通", "EXPENSE", 0xFF64B5F6L),
        CategoryEntity(10, "其他", "EXPENSE", 0xFFBDBDBDL),
        CategoryEntity(11, "工资", "INCOME", 0xFF81C784L),
    )

    private fun row(
        id: Long,
        amount: Long = 0,
        categoryId: Long = 1,
        included: Boolean = false,
        hint: String? = null,
        recognized: Boolean = true,
        merchant: String? = null,
        type: TxType = TxType.EXPENSE,
    ) = ImageImportRow(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        type = type,
        amountCents = amount,
        categoryId = categoryId,
        merchant = merchant,
        txTime = 1_700_000_000_000L,
        included = included,
        hint = hint,
        recognized = recognized,
    )

    @Test
    fun `识别结果逐行展示_确认按钮只数勾选的有效行`() {
        var confirmClicks = 0
        val state = BatchImportUiState(
            visible = true, total = 2, done = 2, analyzing = false,
            rows = listOf(
                row(1, amount = 2503, included = true, merchant = "滴滴出行"),
                row(2, amount = 0, included = false, hint = "没认出账单，可手动补全", recognized = false),
            ),
        )
        rule.setContent {
            KillBillTheme {
                BatchImportScreen(
                    state = state,
                    categories = cats,
                    onToggle = { _, _ -> },
                    onEdit = {},
                    onConfirm = { confirmClicks++ },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("相册批量导入").assertIsDisplayed()
        rule.onNodeWithText("¥25.03").assertIsDisplayed()
        rule.onNodeWithText("滴滴出行").assertIsDisplayed()
        rule.onNodeWithText("没认出账单，可手动补全").assertIsDisplayed()

        rule.onNodeWithText("确认录入（1）").assertIsEnabled().performClick()
        assertEquals(1, confirmClicks)
    }

    @Test
    fun `点勾选框回传行id和取消勾选状态`() {
        var toggled: Pair<Long, Boolean>? = null
        val state = BatchImportUiState(
            visible = true, total = 1, done = 1,
            rows = listOf(row(1, amount = 650, included = true, merchant = "便利店")),
        )
        rule.setContent {
            KillBillTheme {
                BatchImportScreen(
                    state = state,
                    categories = cats,
                    onToggle = { id, checked -> toggled = id to checked },
                    onEdit = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithTag("importCheck_1").performClick()
        assertEquals(1L to false, toggled)
    }

    @Test
    fun `没有可入库的行时确认禁用`() {
        val state = BatchImportUiState(
            visible = true, total = 1, done = 1,
            rows = listOf(row(1, amount = 0, included = false, hint = "图片打不开", recognized = false)),
        )
        rule.setContent {
            KillBillTheme {
                BatchImportScreen(
                    state = state,
                    categories = cats,
                    onToggle = { _, _ -> },
                    onEdit = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("确认录入（0）").assertIsNotEnabled()
    }

    @Test
    fun `识别中展示进度且确认禁用`() {
        val state = BatchImportUiState(
            visible = true, total = 3, done = 1, analyzing = true,
            rows = listOf(row(1, amount = 100, included = true)),
        )
        rule.setContent {
            KillBillTheme {
                BatchImportScreen(
                    state = state,
                    categories = cats,
                    onToggle = { _, _ -> },
                    onEdit = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("正在本地识别 1/3 张…").assertIsDisplayed()
        rule.onNodeWithText("确认录入（1）").assertIsNotEnabled()
    }

    @Test
    fun `点编辑入口回传对应行`() {
        var edited: ImageImportRow? = null
        val target = row(7, amount = 8888, included = true, merchant = "奖金", type = TxType.INCOME)
        val state = BatchImportUiState(
            visible = true, total = 2, done = 2,
            rows = listOf(row(1, amount = 100, included = true), target),
        )
        rule.setContent {
            KillBillTheme {
                BatchImportScreen(
                    state = state,
                    categories = cats,
                    onToggle = { _, _ -> },
                    onEdit = { edited = it },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        rule.onAllNodesWithContentDescription("修改这笔").onFirst().performClick()
        assertEquals(1L, edited?.id)
    }
}

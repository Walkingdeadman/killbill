package com.killbill.ui.transactions

import android.net.Uri
import com.killbill.ai.BillDraft
import com.killbill.ai.ImageBillImporter
import com.killbill.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 相册导入确认页的行映射/勾选/入库条件测试。
 * OCR 本身依赖 ML Kit 无法在 JVM 跑，这里覆盖识别结果之后的业务规则。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchImportModelsTest {

    private val uri = Uri.parse("content://media/external/images/media/42")

    @Test
    fun `识别成功_金额类别就绪_默认勾选且无提示`() {
        val draft = BillDraft(
            isBill = true,
            type = TxType.EXPENSE,
            amountCents = 2503,
            categoryHint = "交通",
            merchant = "滴滴出行",
            note = "快车",
            txTime = 1_700_000_000_000L,
        )
        val row = buildImportRow(
            id = 1,
            uri = uri,
            outcome = ImageBillImporter.Outcome(draft = draft, confident = true),
            categoryId = 3,
        )

        assertEquals(2503L, row.amountCents)
        assertEquals(TxType.EXPENSE, row.type)
        assertEquals(3L, row.categoryId)
        assertEquals("滴滴出行", row.merchant)
        assertEquals("快车", row.note)
        assertEquals(1_700_000_000_000L, row.txTime)
        assertTrue(row.included)
        assertTrue(row.recognized)
        assertNull(row.hint)
        assertTrue(row.readyForImport)
    }

    @Test
    fun `规则没把握_仍默认勾选但提示核对`() {
        val draft = BillDraft(isBill = true, amountCents = 650)
        val row = buildImportRow(
            id = 2,
            uri = uri,
            outcome = ImageBillImporter.Outcome(draft = draft, confident = false),
            categoryId = 10,
        )
        assertTrue(row.included)
        assertEquals("识别把握不大，请核对", row.hint)
    }

    @Test
    fun `没认出账单_不勾选且保留失败原因_等用户手动补全`() {
        val row = buildImportRow(
            id = 3,
            uri = uri,
            outcome = ImageBillImporter.Outcome(error = "没认出账单，可手动补全"),
            categoryId = 10,
        )
        assertEquals(0L, row.amountCents)
        assertEquals(TxType.EXPENSE, row.type)
        assertFalse(row.included)
        assertFalse(row.recognized)
        assertFalse(row.readyForImport)
        assertEquals("没认出账单，可手动补全", row.hint)
        // 没金额时用当前时间兜底，用户补全后即可入库
        assertTrue(row.txTime > 0)
    }

    @Test
    fun `类别解析失败_即使有金额也不勾选`() {
        val draft = BillDraft(isBill = true, amountCents = 1000)
        val row = buildImportRow(
            id = 4,
            uri = uri,
            outcome = ImageBillImporter.Outcome(draft = draft, confident = true),
            categoryId = -1,
        )
        assertFalse(row.included)
        assertFalse(row.readyForImport)
    }

    @Test
    fun `确认按钮只统计勾选且金额类别齐全的行`() {
        val ready = ImageImportRow(
            id = 1, uri = uri, amountCents = 100, categoryId = 1, included = true,
        )
        val unchecked = ImageImportRow(
            id = 2, uri = uri, amountCents = 200, categoryId = 1, included = false,
        )
        val missing = ImageImportRow(
            id = 3, uri = uri, amountCents = 0, categoryId = 1, included = true,
        )
        val state = BatchImportUiState(
            visible = true, total = 3, done = 3, rows = listOf(ready, unchecked, missing),
        )
        assertEquals(1, state.selectedReadyCount)
        assertEquals(1, state.selectedMissingCount)
        assertTrue(state.canConfirm)
    }

    @Test
    fun `识别中或录入中都不许确认`() {
        val row = ImageImportRow(
            id = 1, uri = uri, amountCents = 100, categoryId = 1, included = true,
        )
        val rows = listOf(row)
        assertFalse(BatchImportUiState(visible = true, rows = rows, analyzing = true).canConfirm)
        assertFalse(BatchImportUiState(visible = true, rows = rows, inserting = true).canConfirm)
        assertFalse(BatchImportUiState(visible = true, rows = rows).copy(rows = emptyList()).canConfirm)
    }

    @Test
    fun `编辑弹窗载体字段完整且来源标记相册`() {
        val row = ImageImportRow(
            id = 1,
            uri = uri,
            type = TxType.INCOME,
            amountCents = 8888,
            categoryId = 7,
            merchant = "工资",
            note = "十月",
            txTime = 123L,
        )
        val tx = row.toEditableTransaction()
        assertEquals(0L, tx.id)
        assertEquals(8888L, tx.amountCents)
        assertEquals("INCOME", tx.type)
        assertEquals(7L, tx.categoryId)
        assertEquals("工资", tx.merchant)
        assertEquals("十月", tx.note)
        assertEquals(123L, tx.txTime)
        assertEquals("PHOTO", tx.source)
    }
}

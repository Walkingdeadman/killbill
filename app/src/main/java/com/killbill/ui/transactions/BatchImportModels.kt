package com.killbill.ui.transactions

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.killbill.ai.ImageBillImporter
import com.killbill.data.db.TransactionEntity
import com.killbill.data.db.TxType

/** 一次批量导入最多选几张：本地 OCR 顺序跑，限制数量避免长等待和缩略图占内存 */
const val MAX_BATCH_IMPORT = 20

/**
 * 确认页里的一张图片。字段来自本地 OCR，用户可以在确认前后逐行修改；
 * included=true 且金额/类别有效才会入库。
 */
data class ImageImportRow(
    val id: Long,
    val uri: Uri,
    val thumbnail: ImageBitmap? = null,
    val type: TxType = TxType.EXPENSE,
    /** 金额，单位分。0=未识别，待用户补全 */
    val amountCents: Long = 0,
    val categoryId: Long = -1,
    val merchant: String? = null,
    val note: String? = null,
    val txTime: Long = System.currentTimeMillis(),
    val included: Boolean = false,
    /** 识别提示：失败原因 / "把握不大"；null 表示正常 */
    val hint: String? = null,
    /** 是否至少解析出了账单结构，用于编辑弹窗文案 */
    val recognized: Boolean = false,
) {
    /** 金额和类别都齐了才允许入库 */
    val readyForImport: Boolean get() = amountCents > 0 && categoryId > 0
}

data class BatchImportUiState(
    val visible: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val analyzing: Boolean = false,
    val inserting: Boolean = false,
    val rows: List<ImageImportRow> = emptyList(),
) {
    val selectedReadyCount: Int get() = rows.count { it.included && it.readyForImport }
    val selectedMissingCount: Int get() = rows.count { it.included && !it.readyForImport }
    val canConfirm: Boolean get() = !analyzing && !inserting && selectedReadyCount > 0
}

/**
 * 识别结果 → 确认页行。纯函数，单测覆盖默认选中、失败降级和提示文案。
 *
 * @param categoryId 已按 OCR 类别提示解析出的本地类别 id（未命中为"其他"）
 */
fun buildImportRow(
    id: Long,
    uri: Uri,
    outcome: ImageBillImporter.Outcome,
    categoryId: Long,
    now: Long = System.currentTimeMillis(),
): ImageImportRow {
    val draft = outcome.draft
    val amount = draft?.amountCents ?: 0L
    val ready = amount > 0 && categoryId > 0
    val hint = when {
        outcome.error != null -> outcome.error
        draft != null && !outcome.confident -> "识别把握不大，请核对"
        else -> null
    }
    return ImageImportRow(
        id = id,
        uri = uri,
        thumbnail = outcome.thumbnail?.asImageBitmap(),
        type = draft?.type ?: TxType.EXPENSE,
        amountCents = amount,
        categoryId = categoryId,
        merchant = draft?.merchant,
        note = draft?.note,
        txTime = draft?.txTime ?: now,
        // 认出金额的行默认勾选；没认出的行必须用户手动补全后再勾
        included = draft != null && ready,
        hint = hint,
        recognized = draft != null,
    )
}

/** 编辑弹窗需要 TransactionEntity 作为载体，id=0 表示还没入库 */
fun ImageImportRow.toEditableTransaction(): TransactionEntity = TransactionEntity(
    amountCents = amountCents,
    type = type.name,
    categoryId = categoryId,
    merchant = merchant,
    note = note,
    txTime = txTime,
    source = "PHOTO",
)

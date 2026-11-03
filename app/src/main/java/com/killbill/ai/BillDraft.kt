package com.killbill.ai

import com.killbill.data.db.TxType

/**
 * 识别产出的账单草稿。amountCents=0 表示金额未知，确认卡片进入手动模式（AC4 降级）。
 */
data class BillDraft(
    val isBill: Boolean,
    val type: TxType = TxType.EXPENSE,
    val amountCents: Long = 0,
    val categoryHint: String? = null,
    val merchant: String? = null,
    val txTime: Long? = null,
    val note: String? = null,
    /** 识别失败原因，直接展示在确认卡片上（不许静默失败） */
    val failReason: String? = null,
) {
    val amountUnknown: Boolean get() = amountCents <= 0

    companion object {
        /** 识别失败/降级时的空白手动草稿 */
        fun manual(reason: String? = null) =
            BillDraft(isBill = true, amountCents = 0, failReason = reason)
    }
}

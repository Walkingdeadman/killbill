package com.killbill.data

import com.killbill.ai.BillDraft
import com.killbill.data.db.CategoryDao
import com.killbill.data.db.TransactionDao
import com.killbill.data.db.TransactionEntity
import com.killbill.data.db.TxType

class BillRepository(
    private val txDao: TransactionDao,
    private val catDao: CategoryDao,
) {

    /**
     * 识别草稿入库。categoryHint 模糊匹配本地类别，未命中归"其他"。
     * @return 新交易 id
     */
    suspend fun insertDraft(draft: BillDraft, txTime: Long? = null): Long {
        val categoryId = resolveCategory(draft.categoryHint, draft.type)
        return txDao.insert(
            TransactionEntity(
                amountCents = draft.amountCents,
                type = draft.type.name,
                categoryId = categoryId,
                merchant = draft.merchant,
                note = draft.note,
                txTime = txTime ?: draft.txTime ?: System.currentTimeMillis(),
                source = "CAPTURE",
            )
        )
    }

    suspend fun insertManual(
        amountCents: Long,
        type: TxType,
        categoryId: Long,
        merchant: String?,
        note: String?,
        txTime: Long = System.currentTimeMillis(),
    ): Long = txDao.insert(
        TransactionEntity(
            amountCents = amountCents,
            type = type.name,
            categoryId = categoryId,
            merchant = merchant?.takeIf { it.isNotBlank() },
            note = note?.takeIf { it.isNotBlank() },
            txTime = txTime,
            source = "MANUAL",
        )
    )

    suspend fun update(tx: TransactionEntity) = txDao.update(tx)
    suspend fun delete(tx: TransactionEntity) = txDao.delete(tx)
    suspend fun byId(id: Long) = txDao.byId(id)

    /** 把 categoryHint 映射到本地类别 id：精确 > 包含 > "其他" */
    suspend fun resolveCategory(hint: String?, type: TxType): Long {
        val cats = catDao.byType(type.name)
        val other = cats.firstOrNull { it.name == "其他" }
        if (hint.isNullOrBlank()) return other?.id ?: cats.firstOrNull()?.id ?: -1L
        cats.firstOrNull { it.name == hint }?.let { return it.id }
        cats.firstOrNull { it.name.contains(hint) || hint.contains(it.name) }?.let { return it.id }
        return other?.id ?: cats.firstOrNull()?.id ?: -1L
    }

    suspend fun categoriesFor(type: TxType) = catDao.byType(type.name)
}

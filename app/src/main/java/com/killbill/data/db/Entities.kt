package com.killbill.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 交易类型。全项目统一用这个枚举的 name 存库。 */
enum class TxType { INCOME, EXPENSE }

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** TxType.name: INCOME / EXPENSE */
    val type: String,
    /** ARGB 颜色值，存 Long 避免转换器 */
    val color: Long,
    @ColumnInfo(name = "is_preset") val isPreset: Boolean = false,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [Index("category_id"), Index("tx_time")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 金额，单位：分。全程整数，杜绝浮点误差。 */
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    /** TxType.name: INCOME / EXPENSE */
    val type: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val merchant: String? = null,
    val note: String? = null,
    /** 交易时间 epoch millis */
    @ColumnInfo(name = "tx_time") val txTime: Long,
    /** CAPTURE=截屏识别 / MANUAL=手动 */
    val source: String = "CAPTURE",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

package com.killbill.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    data class TypeSum(val type: String, val total: Long)

    data class CategorySum(
        @ColumnInfo(name = "category_id") val categoryId: Long,
        val name: String,
        val color: Long,
        val total: Long,
        val cnt: Int,
    )

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Delete
    suspend fun delete(tx: TransactionEntity)

    @Query("SELECT type, SUM(amount_cents) AS total FROM transactions WHERE tx_time >= :from AND tx_time < :to GROUP BY type")
    suspend fun sumByType(from: Long, to: Long): List<TypeSum>

    @Query(
        """SELECT t.category_id, c.name, c.color, SUM(t.amount_cents) AS total, COUNT(*) AS cnt
           FROM transactions t JOIN categories c ON c.id = t.category_id
           WHERE t.type = 'EXPENSE' AND t.tx_time >= :from AND t.tx_time < :to
           GROUP BY t.category_id ORDER BY total DESC"""
    )
    suspend fun expenseByCategory(from: Long, to: Long): List<CategorySum>

    @Query("SELECT * FROM transactions ORDER BY tx_time DESC")
    fun allFlow(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @Query("UPDATE transactions SET category_id = :target WHERE category_id = :source")
    suspend fun reassignCategory(source: Long, target: Long)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY type DESC, id")
    fun allFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY id")
    suspend fun byType(type: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByName(name: String, type: String): CategoryEntity?

    @Insert
    suspend fun insert(c: CategoryEntity): Long

    @Delete
    suspend fun delete(c: CategoryEntity)
}

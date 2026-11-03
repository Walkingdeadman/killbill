package com.killbill.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(ctx: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "killbill.db"
                )
                    .addCallback(SEED_CALLBACK)
                    .build()
                    .also { INSTANCE = it }
            }

        /** 预置类别种子。支出在前（UI 按 EXPENSE 优先展示）。 */
        private val PRESET_EXPENSE = listOf(
            Triple("餐饮", 0xFFE57373L, 1),
            Triple("交通", 0xFF64B5F6L, 2),
            Triple("购物", 0xFFF06292L, 3),
            Triple("日用", 0xFFFFB74DL, 4),
            Triple("娱乐", 0xFFBA68C8L, 5),
            Triple("医疗", 0xFF4DB6ACL, 6),
            Triple("住房", 0xFF7986CBL, 7),
            Triple("通讯", 0xFFA1887FL, 8),
            Triple("转账", 0xFF90A4AEL, 9),
            Triple("其他", 0xFFBDBDBDL, 99),
        )

        private val PRESET_INCOME = listOf(
            Triple("工资", 0xFF81C784L, 1),
            Triple("奖金", 0xFFAED581L, 2),
            Triple("理财", 0xFF4DD0E1L, 3),
            Triple("红包", 0xFFFF8A65L, 4),
            Triple("其他", 0xFFBDBDBDL, 99),
        )

        private val SEED_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seed(db, "EXPENSE", PRESET_EXPENSE)
                seed(db, "INCOME", PRESET_INCOME)
            }

            private fun seed(
                db: SupportSQLiteDatabase,
                type: String,
                presets: List<Triple<String, Long, Int>>
            ) {
                presets.forEach { (name, color, order) ->
                    db.execSQL(
                        "INSERT INTO categories (name, type, color, is_preset) VALUES (?, ?, ?, 1)",
                        arrayOf<Any>(name, type, color)
                    )
                    // order 暂未用列存储，预留：按插入顺序即展示顺序
                }
            }
        }
    }
}

package com.killbill.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.killbill.ai.BillDraft
import com.killbill.data.db.AppDatabase
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TxType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BillRepository
    private var foodId = 0L
    private var otherId = 0L

    @Before
    fun setup() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        repo = BillRepository(db.transactionDao(), db.categoryDao())
        foodId = db.categoryDao().insert(CategoryEntity(name = "餐饮", type = "EXPENSE", color = 1))
        otherId = db.categoryDao().insert(CategoryEntity(name = "其他", type = "EXPENSE", color = 2))
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `hint精确命中类别`() = runBlocking {
        assertEquals(foodId, repo.resolveCategory("餐饮", TxType.EXPENSE))
    }

    @Test
    fun `hint未命中归其他`() = runBlocking {
        assertEquals(otherId, repo.resolveCategory("不明消费", TxType.EXPENSE))
        assertEquals(otherId, repo.resolveCategory(null, TxType.EXPENSE))
        assertEquals(otherId, repo.resolveCategory("", TxType.EXPENSE))
    }

    @Test
    fun `草稿入库金额分毫不差`() = runBlocking {
        val draft = BillDraft(
            isBill = true,
            type = TxType.EXPENSE,
            amountCents = 123456,
            categoryHint = "餐饮",
            merchant = "测试商家",
            note = "测试备注",
        )
        val id = repo.insertDraft(draft)
        assertTrue(id > 0)
        val saved = db.transactionDao().byId(id)!!
        assertEquals(123456L, saved.amountCents)
        assertEquals(foodId, saved.categoryId)
        assertEquals("测试商家", saved.merchant)
        assertEquals("CAPTURE", saved.source)
    }
}

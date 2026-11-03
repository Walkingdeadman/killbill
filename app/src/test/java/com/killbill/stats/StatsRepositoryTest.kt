package com.killbill.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.killbill.data.db.AppDatabase
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TransactionEntity
import com.killbill.util.TimeUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.ZoneId

/**
 * 统计对账测试（AC7/AC8）：SQL 聚合、环比边界（上月为 0、跨年 12→1 月）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: StatsRepository
    private var catFood = 0L
    private var catOther = 0L
    private var catSalary = 0L

    @Before
    fun setup() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        repo = StatsRepository(db.transactionDao())
        catFood = db.categoryDao().insert(CategoryEntity(name = "餐饮", type = "EXPENSE", color = 1))
        catOther = db.categoryDao().insert(CategoryEntity(name = "其他", type = "EXPENSE", color = 2))
        catSalary = db.categoryDao().insert(CategoryEntity(name = "工资", type = "INCOME", color = 3))
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun tx(cents: Long, type: String, catId: Long, time: Long) = TransactionEntity(
        amountCents = cents, type = type, categoryId = catId, txTime = time
    )

    private fun atTime(month: YearMonth, day: Int, hour: Int = 12): Long =
        month.atDay(day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `月度汇总与环比正确`() = runBlocking {
        val cur = YearMonth.now()
        val prev = cur.minusMonths(1)
        // 本月：餐饮 50.00 x2 = 100.00；工资 1000.00
        db.transactionDao().insert(tx(5000, "EXPENSE", catFood, atTime(cur, 5)))
        db.transactionDao().insert(tx(5000, "EXPENSE", catFood, atTime(cur, 10)))
        db.transactionDao().insert(tx(100000, "INCOME", catSalary, atTime(cur, 1)))
        // 上月支出 30.00
        db.transactionDao().insert(tx(3000, "EXPENSE", catOther, atTime(prev, 15)))

        val s = repo.load(cur)
        assertEquals(10000L, s.monthExpense)          // AC7：与 SUM 直查一致
        assertEquals(100000L, s.monthIncome)
        assertEquals(3000L, s.prevMonthExpense)
        assertEquals(7000L, s.momDiffCents)            // 100.00 - 30.00
        assertEquals(7000.0 / 3000.0 * 100.0, s.momPct!!, 0.001)  // +233.3%
        // 类别分解
        assertEquals(1, s.breakdown.size)
        assertEquals(catFood, s.breakdown[0].categoryId)
        assertEquals(10000L, s.breakdown[0].total)
        assertEquals(2, s.breakdown[0].cnt)
    }

    @Test
    fun `上月无数据环比为null不除零`() = runBlocking {
        val cur = YearMonth.now()
        db.transactionDao().insert(tx(8888, "EXPENSE", catFood, atTime(cur, 3)))
        val s = repo.load(cur)
        assertEquals(8888L, s.monthExpense)
        assertEquals(0L, s.prevMonthExpense)
        assertNull(s.momDiffCents)   // AC8：显示 "--"
        assertNull(s.momPct)
    }

    @Test
    fun `本月无数据不崩`() = runBlocking {
        val s = repo.load(YearMonth.now().minusMonths(5))
        assertEquals(0L, s.monthExpense)
        assertEquals(0L, s.monthIncome)
        assertNull(s.momDiffCents)
        assertEquals(0, s.breakdown.size)
    }

    @Test
    fun `跨年环比_12月到1月`() = runBlocking {
        val jan = YearMonth.of(2027, 1)
        val dec = YearMonth.of(2026, 12)
        db.transactionDao().insert(tx(20000, "EXPENSE", catFood, atTime(jan, 10)))
        db.transactionDao().insert(tx(10000, "EXPENSE", catFood, atTime(dec, 25)))
        val s = repo.load(jan)
        assertEquals(20000L, s.monthExpense)
        assertEquals(10000L, s.prevMonthExpense)   // 跨年正确回看
        assertEquals(10000L, s.momDiffCents)
        assertEquals(100.0, s.momPct!!, 0.001)
    }

    @Test
    fun `今日汇总只算今天`() = runBlocking {
        val cur = YearMonth.now()
        val today = java.time.LocalDate.now()
        val todayMillis = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.transactionDao().insert(tx(1234, "EXPENSE", catFood, todayMillis))
        // 同月非今天
        val otherDay = if (today.dayOfMonth == 1) today.plusDays(1) else today.withDayOfMonth(1)
        db.transactionDao().insert(
            tx(9999, "EXPENSE", catFood,
                otherDay.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        )
        val s = repo.load(cur)
        assertEquals(1234L, s.dayExpense)
        assertEquals(1234L + 9999L, s.monthExpense)
    }

    @Test
    fun `月份区间边界_月末最后一毫秒算本月`() {
        val cur = YearMonth.now()
        val (start, end) = TimeUtils.monthRange(cur)
        val lastMillis = end - 1
        // 区间 [start, end)：end-1 属于本月，end 属于下月
        assert(lastMillis >= start)
        val (nextStart, _) = TimeUtils.monthRange(cur.plusMonths(1))
        assertEquals(end, nextStart)  // 区间无缝衔接
    }
}

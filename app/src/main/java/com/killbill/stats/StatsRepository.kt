package com.killbill.stats

import com.killbill.data.db.TransactionDao
import com.killbill.util.TimeUtils
import java.time.YearMonth

class StatsRepository(private val dao: TransactionDao) {

    data class Stats(
        val dayIncome: Long = 0,
        val dayExpense: Long = 0,
        val monthIncome: Long = 0,
        val monthExpense: Long = 0,
        val prevMonthExpense: Long = 0,
        val breakdown: List<TransactionDao.CategorySum> = emptyList(),
    ) {
        /** 环比差额（分）。上月无数据返回 null → UI 显示 "--"（AC8） */
        val momDiffCents: Long? get() =
            if (prevMonthExpense <= 0L) null else monthExpense - prevMonthExpense

        /** 环比百分比。上月无数据返回 null */
        val momPct: Double? get() =
            if (prevMonthExpense <= 0L) null
            else (monthExpense - prevMonthExpense) * 100.0 / prevMonthExpense
    }

    suspend fun load(viewMonth: YearMonth): Stats {
        val (monthStart, monthEnd) = TimeUtils.monthRange(viewMonth)
        val (prevStart, prevEnd) = TimeUtils.monthRange(viewMonth.minusMonths(1))
        val (dayStart, dayEnd) = TimeUtils.todayRange()

        val daySums = dao.sumByType(dayStart, dayEnd)
        val monthSums = dao.sumByType(monthStart, monthEnd)
        val prevSums = dao.sumByType(prevStart, prevEnd)

        return Stats(
            dayIncome = daySums.firstOrNull { it.type == "INCOME" }?.total ?: 0,
            dayExpense = daySums.firstOrNull { it.type == "EXPENSE" }?.total ?: 0,
            monthIncome = monthSums.firstOrNull { it.type == "INCOME" }?.total ?: 0,
            monthExpense = monthSums.firstOrNull { it.type == "EXPENSE" }?.total ?: 0,
            prevMonthExpense = prevSums.firstOrNull { it.type == "EXPENSE" }?.total ?: 0,
            breakdown = dao.expenseByCategory(monthStart, monthEnd),
        )
    }
}

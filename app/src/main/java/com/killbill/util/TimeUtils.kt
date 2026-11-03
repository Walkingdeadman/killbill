package com.killbill.util

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object TimeUtils {

    /** [月初, 下月初) 的 epoch millis 区间 */
    fun monthRange(month: YearMonth): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    /** [今天 00:00, 明天 00:00) */
    fun todayRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun monthLabel(month: YearMonth): String {
        val now = YearMonth.now()
        return when (month) {
            now -> "本月"
            now.minusMonths(1) -> "上月"
            else -> "${month.year}年${month.monthValue}月"
        }
    }
}

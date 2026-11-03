package com.killbill.util

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    /** 分 → "12.34" 元字符串 */
    fun yuan(cents: Long): String =
        BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** 分 → "1,234.56" 带千分位 */
    fun fmt(cents: Long): String {
        val bd = BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
        val plain = bd.toPlainString()
        val parts = plain.split(".")
        val intPart = parts[0].reversed().chunked(3).joinToString(",").reversed()
        return if (parts.size > 1) "$intPart.${parts[1]}" else intPart
    }

    /** 带符号：支出 -xx，收入 +xx */
    fun signed(cents: Long, type: String): String =
        if (type == "INCOME") "+${fmt(cents)}" else "-${fmt(cents)}"
}

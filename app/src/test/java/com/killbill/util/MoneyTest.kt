package com.killbill.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `分到元转换`() {
        assertEquals("12.34", Money.yuan(1234))
        assertEquals("0.01", Money.yuan(1))
        assertEquals("0.00", Money.yuan(0))
        assertEquals("100.00", Money.yuan(10000))
    }

    @Test
    fun `千分位格式`() {
        assertEquals("1,234.56", Money.fmt(123456))
        assertEquals("12.34", Money.fmt(1234))
        assertEquals("1,000,000.00", Money.fmt(100000000))
        assertEquals("999.99", Money.fmt(99999))
    }

    @Test
    fun `符号格式`() {
        assertEquals("+12.34", Money.signed(1234, "INCOME"))
        assertEquals("-12.34", Money.signed(1234, "EXPENSE"))
    }

    @Test
    fun `大金额不丢精度`() {
        // 浮点会炸的量级：9万亿分 = 900亿元
        val cents = 9_000_000_000_000L
        assertEquals("90,000,000,000.00", Money.fmt(cents))
    }
}

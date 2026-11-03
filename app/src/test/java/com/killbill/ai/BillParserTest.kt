package com.killbill.ai

import com.killbill.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillParserTest {

    @Test
    fun `标准支出账单解析`() {
        val raw = """{"is_bill": true, "type": "EXPENSE", "amount": "23.50", "category_hint": "餐饮", "merchant": "麦当劳", "tx_time": "2026-10-22 12:30:00", "note": "午餐"}"""
        val d = BillParser.parse(raw)
        assertNotNull(d)
        assertTrue(d!!.isBill)
        assertEquals(TxType.EXPENSE, d.type)
        assertEquals(2350L, d.amountCents)
        assertEquals("餐饮", d.categoryHint)
        assertEquals("麦当劳", d.merchant)
        assertNotNull(d.txTime)
    }

    @Test
    fun `markdown 围栏包裹照样解析`() {
        val raw = """
```json
{"is_bill": true, "type": "INCOME", "amount": "888.88", "category_hint": "红包", "merchant": null, "tx_time": null, "note": null}
```
"""
        val d = BillParser.parse(raw)
        assertNotNull(d)
        assertTrue(d!!.isBill)
        assertEquals(TxType.INCOME, d.type)
        assertEquals(88888L, d.amountCents)
        assertNull(d.merchant)
    }

    @Test
    fun `非账单截图`() {
        val raw = """{"is_bill": false, "type": null, "amount": null, "category_hint": null, "merchant": null, "tx_time": null, "note": null}"""
        val d = BillParser.parse(raw)
        assertNotNull(d)
        assertFalse(d!!.isBill)
    }

    @Test
    fun `畸形 JSON 返回 null 触发降级`() {
        assertNull(BillParser.parse("我觉得这是一笔23元的消费"))
        assertNull(BillParser.parse("{is_bill: true,,,}"))
        assertNull(BillParser.parse(null))
        assertNull(BillParser.parse(""))
    }

    @Test
    fun `金额为 null 时进入手动模式而非解析失败`() {
        val raw = """{"is_bill": true, "type": "EXPENSE", "amount": null, "category_hint": "购物", "merchant": "淘宝", "tx_time": null, "note": null}"""
        val d = BillParser.parse(raw)
        assertNotNull(d)
        assertTrue(d!!.isBill)
        assertEquals(0L, d.amountCents)
        assertTrue(d.amountUnknown)
    }

    @Test
    fun `金额解析各种脏格式`() {
        assertEquals(1234L, BillParser.parseAmountToCents("12.34"))
        assertEquals(123456L, BillParser.parseAmountToCents("¥1,234.56"))
        assertEquals(100L, BillParser.parseAmountToCents("1"))
        assertEquals(1L, BillParser.parseAmountToCents("0.01"))
        // 四舍五入到分
        assertEquals(1235L, BillParser.parseAmountToCents("12.345"))
        assertNull(BillParser.parseAmountToCents(""))
        assertNull(BillParser.parseAmountToCents("null"))
        assertNull(BillParser.parseAmountToCents("abc"))
        assertNull(BillParser.parseAmountToCents("-5"))
    }

    @Test
    fun `时间格式多态解析`() {
        val raw1 = """{"is_bill": true, "type": "EXPENSE", "amount": "1", "category_hint": null, "merchant": null, "tx_time": "2026-10-22 12:30", "note": null}"""
        assertNotNull(BillParser.parse(raw1)!!.txTime)
        val raw2 = """{"is_bill": true, "type": "EXPENSE", "amount": "1", "category_hint": null, "merchant": null, "tx_time": "2026年10月22日 12:30:00", "note": null}"""
        assertNotNull(BillParser.parse(raw2)!!.txTime)
        val raw3 = """{"is_bill": true, "type": "EXPENSE", "amount": "1", "category_hint": null, "merchant": null, "tx_time": "垃圾时间", "note": null}"""
        assertNull(BillParser.parse(raw3)!!.txTime)
    }

    @Test
    fun `字符串 null 值当空处理`() {
        val raw = """{"is_bill": true, "type": "EXPENSE", "amount": "5.00", "category_hint": "null", "merchant": "None", "tx_time": null, "note": ""}"""
        val d = BillParser.parse(raw)!!
        assertNull(d.categoryHint)
        assertNull(d.merchant)
        assertNull(d.note)
    }

    @Test
    fun `思考型模型_推理文本在前JSON在后照样解析`() {
        // 推理文本里还夹了个花括号陷阱
        val raw = """让我分析一下这张图 {这看起来像是一个支付页面}，经过思考，最终输出：
{"is_bill": true, "type": "EXPENSE", "amount": "45.00", "category_hint": "交通", "merchant": "滴滴出行", "tx_time": null, "note": "打车"}"""
        val d = BillParser.parse(raw)
        assertNotNull(d)
        assertTrue(d!!.isBill)
        assertEquals(4500L, d.amountCents)
        assertEquals("滴滴出行", d.merchant)
    }

    @Test
    fun `思考型模型_JSON值内含花括号字符`() {
        val raw = """推理中... 备注里有 { 符号要注意
{"is_bill": true, "type": "EXPENSE", "amount": "9.90", "category_hint": "购物", "merchant": "拼多多", "tx_time": null, "note": "买了个{小}东西"}"""
        val d = BillParser.parse(raw)
        assertNotNull(d)
        assertEquals(990L, d!!.amountCents)
        assertEquals("买了个{小}东西", d.note)
    }
}

package com.killbill.ai

import com.killbill.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 真实账单版式回归（来源为真实账单截图，商户/卡号/金额/日期已匿名化）：
 * 覆盖"标签 值同一行（空格分隔）"、"标签独占一行"、"金额上方商家标题"、
 * 含年份中文时间三种真实切行方式，避免只测标准模板。
 */
class TextBillParserRealLayoutTest {

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `打车账单_商家名在金额上方_标签与值同行`() {
        val lines = listOf(
            "14:30", "64%", "账单详情", "全部账单", "某打车", "-12.34", "自动扣款成功",
            "管理免密支付", "某打车免密支付", "支付时间 2025-10-20 09:35:36",
            "付款方式 招商银行信用卡(****)", "商品说明 打车订单",
            "温馨提示 对此订单有疑问，查看打车服务指南", "乘车时间 2025-10-20 09:15:27",
            "支付奖励 立即领取6积分", "账单分类 交通出行",
        )
        val p = TextBillParser.parse(lines)
        assertNotNull(p)
        val draft = p!!.draft
        assertEquals(1234L, draft.amountCents)
        assertEquals(TxType.EXPENSE, draft.type)
        assertEquals("交通", draft.categoryHint)
        // 金额上方紧邻的商家标题，而不是"识别结果"
        assertEquals("某打车", draft.merchant)
        // 商品说明和值在同一行（空格分隔），不能把下一行"温馨提示"当备注
        assertEquals("打车订单", draft.note)
        assertEquals(millis(2025, 10, 20, 9, 35, 36), draft.txTime)
        assertTrue(p.confident)
    }

    @Test
    fun `微信账单_商户全称与值同行_取全称不取上方店名`() {
        val lines = listOf(
            "14:30", "63%", "全部账单", "某米线店", "-56.78", "当前状态 支付成功",
            "支付时间 2025年10月20日 12:03:13", "商品 微信条码支付",
            "商户全称 西安市高新区某餐饮店(个体工商户)",
            "收单机构 某银行股份有限公司", "支付方式 零钱",
            "交易单号 4200000000000000000000000000",
            "商户单号 可在支持的商户扫码退款",
        )
        val p = TextBillParser.parse(lines)!!
        assertEquals(5678L, p.draft.amountCents)
        assertEquals(TxType.EXPENSE, p.draft.type)
        assertEquals("餐饮", p.draft.categoryHint)
        // 有"商户全称"标签时优先全称，而不是金额上方的简称
        assertEquals("西安市高新区某餐饮店(个体工商户)", p.draft.merchant)
        assertEquals("微信条码支付", p.draft.note)
        // 带年份的中文时间要连秒一起认
        assertEquals(millis(2025, 10, 20, 12, 3, 13), p.draft.txTime)
        assertTrue(p.confident)
    }

    @Test
    fun `OCR分行版式_日期时间无空格也要认`() {
        val lines = listOf(
            "14:30", "64%", "账单详情", "全部账单", "联系商家", "某打车", "-12.34",
            "自动扣款成功", "管理免密支付", "某打车免密支付", "支付时间",
            "2025-10-2009:35:36", "付款方式", "招商银行信用卡(****) >", "商品说明",
            "打车订单", "温馨提示", "对此订单有疑问，查看打车服务指南", "乘车时间",
            "2025-10-2009:15:27", "账单分类", "交通出行 >",
        )
        val p = TextBillParser.parse(lines)!!
        assertEquals(1234L, p.draft.amountCents)
        assertEquals("交通", p.draft.categoryHint)
        assertEquals("某打车", p.draft.merchant)
        assertEquals("打车订单", p.draft.note)
        // OCR 常把日期和时间连成 "2025-10-2009:35:36"，中间没有空格也要能切
        assertEquals(millis(2025, 10, 20, 9, 35, 36), p.draft.txTime)
    }

    @Test
    fun `微信账单_标签与值分行_商户取下一行`() {
        val lines = listOf(
            "14:30", "63%", "全部账单", "某米线店", "-56.78",
            "当前状态", "支付成功", "支付时间", "2025年10月20日12:03:13",
            "商品", "微信条码支付", "商户全称", "西安市高新区某餐饮店(个体工商户)",
            "收单机构", "某银行股份有限公司", "支付方式", "零钱",
        )
        val p = TextBillParser.parse(lines)!!
        assertEquals(5678L, p.draft.amountCents)
        assertEquals("西安市高新区某餐饮店(个体工商户)", p.draft.merchant)
        assertEquals("微信条码支付", p.draft.note)
        assertEquals(millis(2025, 10, 20, 12, 3, 13), p.draft.txTime)
    }

    @Test
    fun `金额上方是全部账单_不误当商户`() {
        val lines = listOf("全部账单", "-9.90", "支付成功", "商品说明", "月卡")
        val p = TextBillParser.parse(lines)!!
        assertEquals(990L, p.draft.amountCents)
        assertNull(p.draft.merchant)
        assertEquals("月卡", p.draft.note)
    }
}

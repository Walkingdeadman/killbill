package com.killbill.ai

import com.killbill.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则解析器对账测试：用例行文本手工还原自 4 张真实截图（testpng/）。
 * 标准答案：滴滴 25.03 / 支付宝三国杀 27.97(订单28.00-红包0.03) / 微信便利店 6.50 / 银行动账地铁 4.00
 */
class TextBillParserTest {

    @Test
    fun `滴滴支付成功页_25_03_交通`() {
        val lines = listOf(
            "09:06", "92%", "准时送达", "绿盟科技西安研发中心", "09:06下车",
            "金辉·世界城D区内部地下停车场", "08:44上车", "紧急求助", "安全中心",
            "支付成功", "享道-特惠快车 | 祝师傅·陕AG97552", "再来一单", "呼叫返程",
            "开发票", "联系客服", "联系司机", "费用疑问", "25.03元", "展开",
            "全程10.68公里，21分钟", "实时单", "费用说明"
        )
        val p = TextBillParser.parse(lines)!!
        assertTrue(p.confident)
        assertEquals(2503L, p.draft.amountCents)
        assertEquals(TxType.EXPENSE, p.draft.type)
        assertEquals("交通", p.draft.categoryHint)
        assertNotNull(p.draft.merchant)
        assertTrue(p.draft.merchant!!.contains("享道"))
        assertNull(p.draft.txTime) // 页面没有完整日期，09:06 是状态栏时间不许误抓
    }

    @Test
    fun `支付宝账单页_实付27_97而非订单28_00`() {
        val lines = listOf(
            "10:49", "账单详情", "全部账单", "三国杀", "-27.97", "交易成功",
            "订单金额", "28.00", "网上消费红包", "-0.03", "支付时间",
            "2026-10-23 09:32:12", "付款方式", "招商银行储蓄卡(3679)", "商品说明", "月卡",
            "支付奖励", "立即领取6积分", "收款方全称", "杭州游卡网络技术有限公司",
            "三国杀", "进入小程序", "账单管理", "账单分类", "文化休闲"
        )
        val p = TextBillParser.parse(lines)!!
        assertTrue(p.confident)
        // 关键：带符号主金额 -27.97 胜出，不是订单金额 28.00，也不是折扣 -0.03
        assertEquals(2797L, p.draft.amountCents)
        assertEquals(TxType.EXPENSE, p.draft.type)
        assertEquals("杭州游卡网络技术有限公司", p.draft.merchant)
        assertEquals("月卡", p.draft.note)
        assertNotNull(p.draft.txTime)
    }

    @Test
    fun `微信支付成功页_6_50_购物`() {
        val lines = listOf("20:54", "支付成功", "盛平烟酒便利店（*通）", "¥6.50", "完成")
        val p = TextBillParser.parse(lines)!!
        assertTrue(p.confident)
        assertEquals(650L, p.draft.amountCents)
        assertEquals("购物", p.draft.categoryHint)
        assertEquals("盛平烟酒便利店（*通）", p.draft.merchant)
    }

    @Test
    fun `银行动账通知_4_00_抓金额行后的商户而非页首旧商户`() {
        val lines = listOf(
            "19:07", "已开启", "动账通知", "交易商户：支付宝-高德打车", "额度查询", "分期还款",
            "查账还款", "我要借钱", "推荐办卡", "企微人工", "11分钟前", "交易成功", "4.00",
            "交易卡号：尾号5858的信用卡（个人消费卡账户）", "交易时间：10月22日18:55",
            "交易类型：消费", "交易币种：人民币",
            "交易商户：支付宝-西安市轨道交通集团有限公司", "额度查询", "分期还款"
        )
        val p = TextBillParser.parse(lines)!!
        assertTrue(p.confident)
        assertEquals(400L, p.draft.amountCents)
        assertEquals("交通", p.draft.categoryHint)
        // 4.00 属于第二张卡（西安轨道），不是页首的高德打车
        assertTrue(p.draft.merchant!!.contains("轨道交通"))
        assertNotNull(p.draft.txTime) // 10月22日18:55 中文日期要认
    }

    @Test
    fun `折扣负数不能当主金额_实付标签优先`() {
        val lines = listOf(
            "支付成功", "订单金额", "28.00", "商家红包", "-0.03",
            "实付金额", "¥27.97", "收款方", "某某公司"
        )
        val p = TextBillParser.parse(lines)!!
        assertEquals(2797L, p.draft.amountCents)
    }

    @Test
    fun `收入页_退款方向识别`() {
        val lines = listOf("交易成功", "退款", "+15.00", "收款方", "美团外卖")
        val p = TextBillParser.parse(lines)!!
        assertEquals(TxType.INCOME, p.draft.type)
        assertEquals(1500L, p.draft.amountCents)
    }

    @Test
    fun `聊天页不是账单`() {
        val lines = listOf("20:54", "老王", "晚上吃什么？", "随便", "那去吃面吧", "好的")
        assertNull(TextBillParser.parse(lines))
    }

    @Test
    fun `有信号无金额_不置信不硬猜`() {
        val lines = listOf("支付成功", "网络异常，金额加载失败")
        assertNull(TextBillParser.parse(lines))
    }
}

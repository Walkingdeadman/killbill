package com.killbill.ai

object BillPrompt {

    const val SYSTEM = "你是一个精确的账单信息提取引擎。你只输出一个 JSON 对象，绝不输出任何其他文字、解释或 markdown 代码块围栏。"

    val USER = """
分析这张手机截图，判断它是否为支付成功页、账单详情页或订单确认页。

输出 JSON，schema 如下：
{"is_bill": true或false, "type": "INCOME"或"EXPENSE", "amount": "字符串数字或null", "category_hint": "字符串或null", "merchant": "字符串或null", "tx_time": "字符串或null", "note": "字符串或null"}

字段规则：
1. is_bill：只有支付成功、转账记录、账单明细、订单金额确认页才是 true。聊天界面、手机桌面、视频、游戏、网页浏览、扫码页、余额首页（无单笔交易金额）都是 false。
2. type：支出为 EXPENSE；入账（收红包、收到转账、工资入账、退款）为 INCOME。
3. amount：本次交易的【实付金额】，即扣除优惠券/红包/积分抵扣后实际支付的数字，单位元，只含数字和小数点，不带货币符号。页面同时出现订单金额和实付金额时，取实付金额。无法确定则为 null。
4. category_hint：从以下列表选最贴切的一个：餐饮、交通、购物、日用、娱乐、医疗、住房、通讯、转账、工资、奖金、理财、红包、其他。无法确定则为 null。
5. merchant：商家名称、店铺名或转账对方名称。无法确定则为 null。
6. tx_time：交易时间，格式 yyyy-MM-dd HH:mm:ss。页面没有完整时间则为 null。
7. note：不超过12个字的交易摘要（如商品名）。无法确定则为 null。
8. is_bill 为 false 时，其余字段全部为 null。

示例输出：
{"is_bill": true, "type": "EXPENSE", "amount": "23.50", "category_hint": "餐饮", "merchant": "麦当劳", "tx_time": null, "note": "午餐"}
""".trimIndent()

    /** 连通性测试用的迷你 prompt */
    const val TEST_USER = "回复内容固定为两个字母: OK"

    /** OCR 文本版 prompt：本地规则没把握时，把 OCR 文字行送模型兜底（比全图省 token） */
    val TEXT_USER = """
以下是手机支付/账单截图的 OCR 识别文字（按行排列，可能有识别误差）。判断它是否为支付成功页、账单页或订单确认页。

输出 JSON，schema 如下：
{"is_bill": true或false, "type": "INCOME"或"EXPENSE", "amount": "字符串数字或null", "category_hint": "字符串或null", "merchant": "字符串或null", "tx_time": "字符串或null", "note": "字符串或null"}

字段规则：
1. is_bill：只有支付成功、转账记录、账单明细、订单金额确认页才是 true；聊天、桌面、文章等均为 false。
2. amount：【实付金额】（扣除优惠/红包后实际支付），单位元；同时出现订单金额与实付金额时取实付。
3. category_hint：从[餐饮,交通,购物,日用,娱乐,医疗,住房,通讯,转账,工资,奖金,理财,红包,其他]选最贴切的一个。
4. merchant：商家/店铺/收款方名称。
5. tx_time：交易时间，格式 yyyy-MM-dd HH:mm:ss，无则 null。
6. note：不超过12字的交易摘要。
7. is_bill 为 false 时其余字段全 null。
只输出 JSON 对象本身。
""".trimIndent()
}

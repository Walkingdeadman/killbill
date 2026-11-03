package com.killbill.ai

import com.killbill.data.db.TxType
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 本地规则解析器：OCR 文字行 → 账单草稿。零网络、毫秒级。
 *
 * 针对微信/支付宝/滴滴/银行动账通知等模板化页面调过规则（见 TextBillParserTest）。
 * 置信度不够时上层把文本送模型兜底，不硬猜。
 */
object TextBillParser {

    data class Parsed(val draft: BillDraft, val confident: Boolean)

    private val BILL_SIGNALS = listOf(
        "支付成功", "付款成功", "交易成功", "账单详情", "收款方", "实付",
        "订单金额", "交易商户", "动账通知", "扣款成功", "支付凭证"
    )

    private val AMOUNT_LABELS = listOf("实付", "实付款", "实际支付", "支付金额", "合计", "应付")

    private val NOISE_WORDS = listOf("公里", "分钟", "积分", "折", "%", "券", "里程")

    private val CATEGORY_RULES: List<Pair<List<String>, String>> = listOf(
        listOf("打车", "快车", "滴滴", "高德", "地铁", "轨道", "公交", "铁路", "火车", "加油", "停车", "用车") to "交通",
        listOf("外卖", "麦当劳", "肯德基", "美团", "饿了么", "咖啡", "奶茶", "餐厅", "餐饮", "早饭", "午饭", "晚饭") to "餐饮",
        listOf("药", "医院", "诊所", "门诊") to "医疗",
        listOf("游戏", "月卡", "会员", "影视", "音乐", "娱乐", "充值") to "娱乐",
        listOf("房租", "物业", "水电", "燃气", "房租") to "住房",
        listOf("话费", "流量", "宽带", "通信") to "通讯",
        listOf("超市", "便利店", "商场", "淘宝", "京东", "拼多多", "百货") to "购物",
    )

    private val INCOME_SIGNALS = listOf("退款", "收入", "工资", "薪资", "奖金", "红包", "理财收益", "利息")

    private val MERCHANT_LABELS = listOf(
        "收款方全称", "收款方", "商户全称", "商户名称", "交易商户", "商家名称", "商家"
    )

    /** 金额行上方紧邻的短标题不能当商户名的 UI 文案（微信/支付宝商家名→金额版式） */
    private val MERCHANT_TITLE_BLOCKLIST = listOf(
        "账单", "详情", "全部账单", "当前状态", "支付成功", "付款成功", "交易成功",
        "扣款成功", "自动扣款", "付款方式", "商品", "支付时间", "乘车时间",
        "收单机构", "商户单号", "交易单号", "账单分类", "温馨提示", "推荐服务",
        "查看行程", "去查看", "更多", "标签", "请选择", "为您推荐", "支付宝",
        "微信", "云闪付", "零钱", "积分", "奖励", "金额", "收款", "付款", "服务",
        "实付", "合计", "应付", "订单",
    )

    private val moneyWithSymbol = Regex("[¥￥]\\s*([+-]?\\d+(?:\\.\\d{1,2})?)")
    private val moneyWithYuan = Regex("([+-]?\\d+(?:\\.\\d{1,2})?)\\s*元")
    private val moneyBare = Regex("^([+-]?\\d+\\.\\d{1,2})$")
    private val dateTimeFull = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private val dateTimeCnFull = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private val dateTimeCn = Regex("(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})")

    fun parse(lines: List<String>): Parsed? {
        val text = lines.joinToString("\n")
        if (BILL_SIGNALS.none { text.contains(it) }) return null

        // ---- 金额候选 ---- kind: 0=¥符号 1=元后缀 2=裸数字 3=实付/合计标签行
        val cands = mutableListOf<Cand>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (NOISE_WORDS.any { line.contains(it) }) return@forEachIndexed
            val labeled = AMOUNT_LABELS.firstOrNull { line.contains(it) } != null
            val m = moneyWithSymbol.find(line)
                ?: moneyWithYuan.find(line)
                ?: moneyBare.find(line)
                ?: return@forEachIndexed
            val raw = m.groupValues[1]
            val cents = BillParser.parseSignedAmountToCents(raw) ?: return@forEachIndexed
            val kind = when {
                labeled -> 3
                moneyWithSymbol.containsMatchIn(line) -> 0
                moneyWithYuan.containsMatchIn(line) -> 1
                else -> 2
            }
            cands.add(Cand(cents, i, kind, plus = raw.startsWith("+")))
        }
        if (cands.isEmpty()) return null

        val amount = pickAmount(cands) ?: return null

        // ---- 收支方向 ----
        val type = when {
            amount.plus -> TxType.INCOME                    // "+15.00" 明牌收入
            amount.cents < 0 -> TxType.EXPENSE              // "-27.97" 明牌支出
            INCOME_SIGNALS.any { text.contains(it) } &&
                !text.contains("消费") && !text.contains("支付成功") -> TxType.INCOME
            else -> TxType.EXPENSE
        }
        val absCents = kotlin.math.abs(amount.cents)

        // ---- 商家 ----
        val merchant = pickMerchant(lines, amount.idx)

        // ---- 时间 ----
        val txTime = pickTime(lines)

        // ---- 类别 ----
        val category = CATEGORY_RULES.firstOrNull { (kws, _) -> kws.any { text.contains(it) } }?.second

        // ---- 备注：商品说明/商品 ----
        val note = labeledNextLine(lines, listOf("商品说明", "商品", "备注", "内容"))

        val draft = BillDraft(
            isBill = true,
            type = type,
            amountCents = absCents,
            categoryHint = category,
            merchant = merchant,
            txTime = txTime,
            note = note,
        )
        val confident = merchant != null || category != null
        return Parsed(draft, confident)
    }

    /** 标签行(实付/合计) > ¥符号 > 元后缀 > 裸数字，同级取第一个。
     *  带符号负数（支付宝 -27.97）在裸数字之前被 ¥/元 规则跳过时，由负数优先兜底。 */
    private fun pickAmount(cands: List<Cand>): Cand? {
        val maxPos = cands.maxOfOrNull { if (it.cents > 0) it.cents else 0L } ?: 0L
        val neg = cands.filter { it.cents < 0 }.minByOrNull { it.cents }
        // 主金额是负数（支付宝 -27.97）：负数绝对值需达到最大正数的一半，
        // 否则那负数只是折扣（红包 -0.03），走标签/符号规则
        if (neg != null && (maxPos == 0L || -neg.cents >= maxPos / 2)) return neg
        for (kind in listOf(3, 0, 1, 2)) {
            cands.firstOrNull { it.kind == kind && it.cents > 0 }?.let { return it }
        }
        return cands.firstOrNull()
    }

    private fun pickMerchant(lines: List<String>, amountIdx: Int): String? {
        // 1) "收款方/商户全称"等标签：优先取同一行冒号/空格后的值，标签独占一行则取下一行
        val labeled = lines.mapIndexedNotNull { i, l ->
            val label = MERCHANT_LABELS.firstOrNull {
                l.startsWith(it) || l.contains("：$it") || l.contains(": $it")
            }
            if (label == null) null else {
                val value = valueAfterLabel(l, label)
                    ?: lines.getOrNull(i + 1)?.trim()
                if (value.isNullOrBlank()) null else (i to value)
            }
        }
        labeled.firstOrNull { it.first > amountIdx }?.let { return it.second }
        labeled.lastOrNull()?.let { return it.second }

        val merchantWords = listOf("店", "公司", "快车", "商户")
        // 2) "支付成功/交易成功"信号行的下一行（滴滴/微信版式：信号→商家→金额）
        val signalIdx = lines.indexOfFirst { l ->
            l.contains("支付成功") || l.contains("交易成功") || l.contains("付款成功")
        }
        if (signalIdx >= 0) {
            lines.getOrNull(signalIdx + 1)?.trim()?.let { next ->
                if (next.length in 3..25 && merchantWords.any { next.contains(it) }) return next
            }
        }
        // 3) 金额行之前含"店/公司/商户"的短行（不含"中心"——地图 POI 陷阱，如"XX研发中心"）
        lines.take(amountIdx).lastOrNull { l ->
            l.length in 3..20 && merchantWords.any { l.contains(it) }
        }?.let { return it.trim() }
        // 4) 金额行上方紧邻的短标题（支付宝/微信账单常见：商家名 → 金额）
        lines.getOrNull(amountIdx - 1)?.trim()?.let { above ->
            if (looksLikeMerchantTitle(above)) return above
        }
        return null
    }

    /**
     * 标签行取值：兼容 "商户全称：xxx"、"支付时间: xxx"，以及真实账单里常见的
     * "商户全称 xxx"（标签与值之间只有空格）。取不到返回 null，交给下一行兜底。
     */
    private fun valueAfterLabel(line: String, label: String): String? {
        val idx = line.indexOf(label)
        if (idx < 0) return null
        val rest = line.substring(idx + label.length).trim()
        val cleaned = rest.removePrefix("：").removePrefix(":").trim()
        return cleaned.takeIf { it.isNotEmpty() && it != line.trim() }
    }

    /** 金额上方那行是不是可当商户名的短标题；过滤"全部账单/支付成功"等 UI 文案 */
    private fun looksLikeMerchantTitle(line: String): Boolean {
        val t = line.trim()
        if (t.length !in 2..25) return false
        if (t.startsWith("¥") || t.startsWith("￥")) return false
        if (t.endsWith("：") || t.endsWith(":")) return false
        if (dateTimeFull.containsMatchIn(t) || dateTimeCnFull.containsMatchIn(t) ||
            dateTimeCn.containsMatchIn(t)
        ) return false
        if (t.any { it.isDigit() } && t.none { it.isLetter() }) return false
        if (MERCHANT_TITLE_BLOCKLIST.any { t.contains(it) }) return false
        return true
    }

    private fun pickTime(lines: List<String>): Long? {
        for (line in lines) {
            dateTimeFull.find(line)?.let { m ->
                val (y, mo, d, h, mi) = listOf(1, 2, 3, 4, 5).map { m.groupValues[it].toInt() }
                val s = m.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
                return runCatching {
                    LocalDateTime.of(y, mo, d, h, mi, s)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
        }
        for (line in lines) {
            dateTimeCnFull.find(line)?.let { m ->
                val (y, mo, d, h, mi) = listOf(1, 2, 3, 4, 5).map { m.groupValues[it].toInt() }
                val s = m.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
                return runCatching {
                    LocalDateTime.of(y, mo, d, h, mi, s)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
        }
        for (line in lines) {
            dateTimeCn.find(line)?.let { m ->
                val (mo, d, h, mi) = listOf(1, 2, 3, 4).map { m.groupValues[it].toInt() }
                val year = LocalDateTime.now().year
                return runCatching {
                    LocalDateTime.of(year, mo, d, h, mi)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
        }
        return null
    }

    private fun labeledNextLine(lines: List<String>, labels: List<String>): String? {
        lines.forEachIndexed { i, l ->
            val label = labels.firstOrNull { l.startsWith(it) || l.contains("：$it") }
            if (label != null) {
                valueAfterLabel(l, label)?.let { return it }
                lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private data class Cand(val cents: Long, val idx: Int, val kind: Int, val plus: Boolean = false)
}

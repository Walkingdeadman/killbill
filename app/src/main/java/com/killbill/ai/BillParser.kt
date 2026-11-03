package com.killbill.ai

import com.killbill.data.db.TxType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 解析 Qwen-VL 返回文本 → BillDraft。
 * 容错设计（AC4）：剥 markdown 围栏、截取首个 JSON 对象、任何解析失败返回 null 由上层降级。
 */
object BillParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val timeFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"),
    )

    fun parse(raw: String?): BillDraft? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = extractJsonObject(raw)
            val isBill = obj["is_bill"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!isBill) return BillDraft(isBill = false)

            val type = when (obj["type"]?.jsonPrimitive?.contentOrNull?.uppercase()) {
                "INCOME" -> TxType.INCOME
                else -> TxType.EXPENSE
            }
            BillDraft(
                isBill = true,
                type = type,
                amountCents = obj.str("amount")?.let { parseAmountToCents(it) } ?: 0L,
                categoryHint = obj.str("category_hint"),
                merchant = obj.str("merchant"),
                txTime = obj.str("tx_time")?.let { parseTime(it) },
                note = obj.str("note"),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 带符号版："-27.97" → -2797、"+15.00" → +1500。OCR 文本解析用（账单主金额可能带负号）。 */
    fun parseSignedAmountToCents(s: String): Long? {
        val cleaned = s.replace(Regex("[^0-9.\\-+]"), "")
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == "+" || cleaned == ".") return null
        return try {
            BigDecimal(cleaned)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
                .takeIf { it != 0L }
        } catch (e: Exception) {
            null
        }
    }

    /** "12.34" / "¥1,234.5" → 1234L(分)。四舍五入到分。解析不出返回 null。 */
    fun parseAmountToCents(s: String): Long? {
        val cleaned = s.replace(Regex("[^0-9.\\-]"), "")
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
        return try {
            BigDecimal(cleaned)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
                .takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.str(key: String): String? {
        val c = this[key]?.jsonPrimitive?.contentOrNull ?: return null
        val t = c.trim()
        return if (t.isEmpty() || t == "null" || t == "None") null else t
    }

    /**
     * 从尾部反向扫描，提取最后一个配平的 JSON 对象。
     * 兼容思考型模型：推理文本在前、答案 JSON 在后，推理里夹杂花括号也不受影响。
     * 反向扫描失败再退回"首 { 到尾 }"的宽松截取。
     */
    private fun extractJsonObject(s: String): JsonObject {
        val end = s.lastIndexOf('}')
        if (end < 0) throw IllegalArgumentException("no json object found")
        var depth = 0
        var inStr = false
        var esc = false
        for (i in end downTo 0) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '}' -> depth++
                '{' -> {
                    depth--
                    if (depth == 0) {
                        return json.parseToJsonElement(s.substring(i, end + 1)).jsonObject
                    }
                }
            }
        }
        // 反向扫描没配平上：退回宽松截取
        val start = s.indexOf('{')
        if (start < 0 || end <= start) throw IllegalArgumentException("no json object found")
        return json.parseToJsonElement(s.substring(start, end + 1)).jsonObject
    }

    private fun parseTime(s: String): Long? {
        for (fmt in timeFormatters) {
            try {
                val ldt = LocalDateTime.parse(s.trim(), fmt)
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        // 只有日期的情况
        try {
            val d = s.trim().replace(Regex("[年月/]"), "-").removeSuffix("日")
            val parts = d.split("-").mapNotNull { it.toIntOrNull() }
            if (parts.size >= 3) {
                val ldt = LocalDateTime.of(parts[0], parts[1], parts[2], 0, 0, 0)
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        } catch (_: Exception) {
        }
        return null
    }
}

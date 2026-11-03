package com.killbill.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.killbill.data.db.TransactionEntity
import com.killbill.ui.AppViewModel
import com.killbill.ui.components.RingChart
import com.killbill.ui.components.RingSlice
import com.killbill.ui.theme.DownGreen
import com.killbill.ui.theme.ExpenseRed
import com.killbill.ui.theme.IncomeGreen
import com.killbill.ui.theme.TextGray
import com.killbill.ui.theme.UpRed
import com.killbill.util.Money
import com.killbill.util.TimeUtils
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun StatsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val stats = vm.stats
    val month = vm.viewMonth
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    var selectedCatId by remember { mutableStateOf<Long?>(null) }

    val (monthStart, monthEnd) = remember(month) { TimeUtils.monthRange(month) }
    val slices = stats.breakdown.map {
        RingSlice(it.categoryId, it.name, it.color, it.total)
    }
    val monthTotal = stats.monthExpense

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ---- 月份切换 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { vm.prevMonth(); selectedCatId = null }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
            }
            Text(
                text = "${month.year}年${month.monthValue}月 · ${TimeUtils.monthLabel(month)}",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = { vm.nextMonth(); selectedCatId = null },
                enabled = month < YearMonth.now()
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
            }
        }

        // ---- 今日卡片 ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SumCard(
                modifier = Modifier.weight(1f),
                title = "今日支出",
                amount = Money.fmt(stats.dayExpense),
                amountColor = ExpenseRed,
            )
            SumCard(
                modifier = Modifier.weight(1f),
                title = "今日收入",
                amount = Money.fmt(stats.dayIncome),
                amountColor = IncomeGreen,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SumCard(
                modifier = Modifier.weight(1f),
                title = "${TimeUtils.monthLabel(month)}支出",
                amount = Money.fmt(stats.monthExpense),
                amountColor = ExpenseRed,
            )
            SumCard(
                modifier = Modifier.weight(1f),
                title = "${TimeUtils.monthLabel(month)}收入",
                amount = Money.fmt(stats.monthIncome),
                amountColor = IncomeGreen,
            )
        }

        // ---- 环比卡片（AC8）----
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("相较上月支出", fontSize = 13.sp, color = TextGray)
                    Spacer(Modifier.height(4.dp))
                    val diff = stats.momDiffCents
                    val pct = stats.momPct
                    if (diff == null || pct == null) {
                        Text("--", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextGray)
                        Text("上月无支出数据", fontSize = 12.sp, color = TextGray)
                    } else {
                        val up = diff > 0
                        val color = if (diff == 0L) TextGray else if (up) UpRed else DownGreen
                        val sign = if (up) "+" else if (diff < 0) "-" else ""
                        Text(
                            "$sign¥${Money.fmt(abs(diff))}",
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color
                        )
                        Text(
                            buildString {
                                append(if (up) "↑ 涨了 " else if (diff < 0) "↓ 省了 " else "持平 ")
                                append(String.format("%.1f%%", abs(pct)))
                            },
                            fontSize = 12.sp, color = color
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("上月支出", fontSize = 13.sp, color = TextGray)
                    Text(
                        "¥${Money.fmt(stats.prevMonthExpense)}",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ---- 类别占比（AC9）----
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("支出类别占比", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                if (monthTotal <= 0) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("本月还没有支出记录", color = TextGray, fontSize = 14.sp)
                    }
                } else {
                    RingChart(
                        slices = slices,
                        totalCents = monthTotal,
                        selectedId = selectedCatId,
                        onSelect = { selectedCatId = it },
                        modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(16.dp))
                    slices.forEach { s ->
                        val pct = if (monthTotal > 0) s.cents * 100.0 / monthTotal else 0.0
                        val dim = selectedCatId != null && selectedCatId != s.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCatId = if (selectedCatId == s.id) null else s.id
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        Color(s.colorArgb.toInt()).copy(alpha = if (dim) 0.35f else 1f),
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                s.label,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                color = if (dim) TextGray else Color.Unspecified
                            )
                            Text(
                                String.format("%.1f%%", pct),
                                fontSize = 13.sp,
                                color = TextGray,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(
                                "¥${Money.fmt(s.cents)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (dim) TextGray else Color.Unspecified
                            )
                        }
                    }
                }
            }
        }

        // ---- 选中类别的明细联动（AC9）----
        selectedCatId?.let { catId ->
            val catTx = remember(transactions, catId, monthStart, monthEnd) {
                transactions.filter {
                    it.categoryId == catId && it.type == "EXPENSE" &&
                            it.txTime in monthStart until monthEnd
                }
            }
            val catName = slices.firstOrNull { it.id == catId }?.label ?: "该类别"
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "「$catName」本月明细（${catTx.size}笔）",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    if (catTx.isEmpty()) {
                        Text("没有记录", fontSize = 13.sp, color = TextGray)
                    } else {
                        val fmt = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
                        catTx.forEach { tx: TransactionEntity ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        tx.merchant ?: tx.note ?: catName,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        Instant.ofEpochMilli(tx.txTime)
                                            .atZone(ZoneId.systemDefault()).format(fmt),
                                        fontSize = 11.sp, color = TextGray
                                    )
                                }
                                Text(
                                    "-¥${Money.fmt(tx.amountCents)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ExpenseRed
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SumCard(
    modifier: Modifier,
    title: String,
    amount: String,
    amountColor: Color,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 13.sp, color = TextGray)
            Spacer(Modifier.height(6.dp))
            Text(
                "¥$amount",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                maxLines = 1,
            )
        }
    }
}

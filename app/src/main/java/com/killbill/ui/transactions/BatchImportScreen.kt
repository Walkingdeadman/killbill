package com.killbill.ui.transactions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TxType
import com.killbill.ui.theme.BgGray
import com.killbill.ui.theme.ExpenseRed
import com.killbill.ui.theme.IncomeGreen
import com.killbill.ui.theme.TextGray
import com.killbill.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 相册批量导入确认页：本地 OCR 结果逐张列出，勾选 + 逐笔核对，确认后才入库。
 * 没认出的图不默认勾选，避免把空金额记进账本。
 */
@Composable
fun BatchImportScreen(
    state: BatchImportUiState,
    categories: List<CategoryEntity>,
    onToggle: (Long, Boolean) -> Unit,
    onEdit: (ImageImportRow) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catMap = categories.associateBy { it.id }
    val recognized = state.rows.count { it.recognized }

    Column(modifier.fillMaxSize().background(BgGray).testTag("batchImportScreen")) {
        // ---- 头部 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, enabled = !state.inserting) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("相册批量导入", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "本地 OCR 识别，确认后才写进账本",
                    fontSize = 12.sp,
                    color = TextGray,
                )
            }
        }

        // ---- 进度 / 汇总 ----
        if (state.analyzing) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LinearProgressIndicator(
                    progress = {
                        if (state.total <= 0) 0f else state.done.toFloat() / state.total
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "正在本地识别 ${state.done}/${state.total} 张…",
                    fontSize = 12.sp,
                    color = TextGray,
                )
            }
        } else {
            Text(
                buildString {
                    append("共 ${state.total} 张图")
                    if (state.rows.isNotEmpty()) append("，认出 $recognized 笔")
                    if (state.selectedMissingCount > 0) append("，还有 ${state.selectedMissingCount} 笔待补全")
                },
                fontSize = 12.sp,
                color = TextGray,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // ---- 逐张确认列表 ----
        if (state.rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.analyzing) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    Text("这些图片都没识别出账单内容", fontSize = 14.sp, color = TextGray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    ImportRowCard(
                        row = row,
                        categoryName = catMap[row.categoryId]?.name,
                        enabled = !state.inserting,
                        onToggle = { onToggle(row.id, it) },
                        onEdit = { onEdit(row) },
                    )
                }
            }
        }

        // ---- 底部确认 ----
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    when {
                        state.inserting -> "正在写入账本…"
                        state.analyzing -> "识别还没跑完，稍等再确认"
                        state.selectedMissingCount > 0 -> "有 ${state.selectedMissingCount} 笔缺金额或类别，点右侧 ✎ 补全，或取消勾选"
                        state.selectedReadyCount > 0 -> "只会录入勾选的 ${state.selectedReadyCount} 笔，确认前都可以改"
                        else -> "勾选要录入的账单，再点确认"
                    },
                    fontSize = 12.sp,
                    color = if (state.selectedMissingCount > 0) ExpenseRed else TextGray,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !state.inserting) {
                        Text("取消")
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onConfirm, enabled = state.canConfirm) {
                        if (state.inserting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (state.inserting) "录入中…" else "确认录入（${state.selectedReadyCount}）")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportRowCard(
    row: ImageImportRow,
    categoryName: String?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val catName = categoryName ?: "其他"
    val title = row.merchant?.takeIf { it.isNotBlank() }
        ?: row.note?.takeIf { it.isNotBlank() }
        ?: if (row.recognized) catName else "未识别账单"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.included,
                onCheckedChange = { if (enabled) onToggle(it) },
                enabled = enabled,
                modifier = Modifier.testTag("importCheck_${row.id}"),
            )
            Thumbnail(row)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (row.amountCents > 0) "¥${Money.fmt(row.amountCents)}" else "金额待补全",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            row.amountCents <= 0 -> TextGray
                            row.type == TxType.INCOME -> IncomeGreen
                            else -> ExpenseRed
                        },
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (row.type == TxType.INCOME) "收入" else "支出",
                        fontSize = 11.sp,
                        color = TextGray,
                    )
                }
                Text(
                    title,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = row.hint ?: buildString {
                    append(catName)
                    append(" · ")
                    append(
                        Instant.ofEpochMilli(row.txTime)
                            .atZone(ZoneId.systemDefault())
                            .format(TIME_FMT)
                    )
                }
                Text(
                    sub,
                    fontSize = 11.sp,
                    color = if (row.hint != null) ExpenseRed else TextGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Filled.Edit, contentDescription = "修改这笔", tint = TextGray)
            }
        }
    }
}

@Composable
private fun Thumbnail(row: ImageImportRow) {
    val shape = RoundedCornerShape(8.dp)
    val bmp = row.thumbnail
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "第 ${row.id} 张图片",
            modifier = Modifier.size(54.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier.size(54.dp).clip(shape).background(Color(0xFFEDEDED)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Image, contentDescription = "第 ${row.id} 张图片", tint = TextGray)
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

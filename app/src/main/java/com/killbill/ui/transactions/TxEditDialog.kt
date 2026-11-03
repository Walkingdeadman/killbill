package com.killbill.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.killbill.ai.BillParser
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TransactionEntity
import com.killbill.data.db.TxType
import com.killbill.ui.theme.TextGray
import com.killbill.util.Money

/**
 * 交易编辑/新增对话框。initial=null 为新增模式。
 *
 * 注意：类别选择用 FlowRow chips，不用 ExposedDropdownMenuBox——
 * 下拉菜单塞 AlertDialog 会触发无限重测量（Compose 永不 idle，启动对话框即 ANR/闪退级卡顿）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxEditDialog(
    initial: TransactionEntity?,
    categories: List<CategoryEntity>,
    onSave: (TransactionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember {
        mutableStateOf(if (initial?.type == "INCOME") TxType.INCOME else TxType.EXPENSE)
    }
    var amountText by remember {
        mutableStateOf(initial?.let { Money.yuan(it.amountCents) } ?: "")
    }
    var merchant by remember { mutableStateOf(initial?.merchant ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }

    val typeCats = categories.filter { it.type == type.name }
    var categoryId by remember(type, categories, initial) {
        mutableStateOf(
            initial?.categoryId?.takeIf { id -> categories.any { it.id == id && it.type == type.name } }
                ?: categories.filter { it.type == type.name }
                    .firstOrNull { it.name == "其他" }?.id
                ?: categories.firstOrNull { it.type == type.name }?.id ?: -1L
        )
    }

    fun switchType(t: TxType) {
        if (t == type) return
        type = t
        val newCats = categories.filter { it.type == t.name }
        categoryId = newCats.firstOrNull { it.name == "其他" }?.id
            ?: newCats.firstOrNull()?.id ?: -1L
    }

    val amountCents = BillParser.parseAmountToCents(amountText)
    val canSave = amountCents != null && amountCents > 0 && categoryId > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "手动记一笔" else "编辑账目") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 收/支切换
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TxType.EXPENSE,
                        onClick = { switchType(TxType.EXPENSE) },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = type == TxType.INCOME,
                        onClick = { switchType(TxType.INCOME) },
                        label = { Text("收入") }
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    prefix = { Text("¥") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 类别 chips
                Text("类别", fontSize = 13.sp, color = TextGray)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    typeCats.forEach { c ->
                        FilterChip(
                            selected = c.id == categoryId,
                            onClick = { categoryId = c.id },
                            label = { Text(c.name, fontSize = 13.sp) },
                        )
                    }
                }

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商家（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!canSave || amountCents == null) return@TextButton
                    val tx = (initial ?: TransactionEntity(
                        amountCents = 0,
                        type = type.name,
                        categoryId = categoryId,
                        txTime = System.currentTimeMillis(),
                        source = "MANUAL",
                    )).copy(
                        amountCents = amountCents,
                        type = type.name,
                        categoryId = categoryId,
                        merchant = merchant.trim().ifBlank { null },
                        note = note.trim().ifBlank { null },
                    )
                    onSave(tx)
                },
                enabled = canSave
            ) { Text("保存", color = if (canSave) Color.Unspecified else TextGray) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

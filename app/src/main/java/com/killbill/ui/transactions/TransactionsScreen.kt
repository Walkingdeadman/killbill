package com.killbill.ui.transactions

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TransactionEntity
import com.killbill.data.db.TxType
import com.killbill.ui.AppViewModel
import com.killbill.ui.theme.ExpenseRed
import com.killbill.ui.theme.IncomeGreen
import com.killbill.ui.theme.TextGray
import com.killbill.util.Money
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val catMap = remember(categories) { categories.associateBy { it.id } }

    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var editingImport by remember { mutableStateOf<ImageImportRow?>(null) }
    val context = LocalContext.current

    // 相册批量导入：系统 Photo Picker 多选，无需额外相册权限。
    // 选完立即进确认页跑本地 OCR，用户核对后才入库。
    val pickerContract = remember {
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH_IMPORT)
    }
    val importPicker = rememberLauncherForActivityResult(pickerContract) { uris ->
        if (uris.isNotEmpty()) {
            // API 33+ 由系统 picker 限张数；老系统走 OPEN_DOCUMENT 不限，这里兜底并告知
            if (uris.size > MAX_BATCH_IMPORT) {
                Toast.makeText(
                    context,
                    "一次最多导入 $MAX_BATCH_IMPORT 张，已取前 $MAX_BATCH_IMPORT 张",
                    Toast.LENGTH_LONG,
                ).show()
            }
            vm.startImageImport(uris)
        }
    }

    val importResult = vm.importResult
    LaunchedEffect(importResult) {
        if (importResult != null) {
            Toast.makeText(context, importResult, Toast.LENGTH_SHORT).show()
            vm.clearImportResult()
        }
    }

    val importState = vm.batchImport
    BackHandler(enabled = importState.visible) { vm.dismissImageImport() }

    if (importState.visible) {
        BatchImportScreen(
            state = importState,
            categories = categories,
            onToggle = { id, checked -> vm.toggleImportRow(id, checked) },
            onEdit = { editingImport = it },
            onConfirm = { vm.confirmImageImport() },
            onDismiss = { vm.dismissImageImport() },
            modifier = modifier,
        )
    } else {
        val grouped = remember(transactions) {
            transactions.groupBy {
                YearMonth.from(Instant.ofEpochMilli(it.txTime).atZone(ZoneId.systemDefault()))
            }.toSortedMap(compareByDescending { it })
        }

        Box(modifier = modifier.fillMaxSize()) {
            if (transactions.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("还没有账目", fontSize = 16.sp, color = TextGray)
                    Spacer(Modifier.height(6.dp))
                    Text("点右下角 + 手动记一笔，或「相册导入」批量识别", fontSize = 13.sp, color = TextGray)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    grouped.forEach { (month, list) ->
                        item(key = "header_$month") {
                            MonthHeader(month, list)
                        }
                        items(list, key = { it.id }) { tx ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        pendingDelete = tx
                                    }
                                    false // 永远不真的滑没，弹确认框
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(ExpenseRed.copy(alpha = 0.15f))
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = ExpenseRed)
                                    }
                                }
                            ) {
                                TxRow(
                                    tx = tx,
                                    category = catMap[tx.categoryId],
                                    onClick = { editing = tx },
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(148.dp)) }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        runCatching {
                            importPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }.onFailure {
                            Toast.makeText(context, "打不开相册选图", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                    text = { Text("相册导入") },
                )
                FloatingActionButton(
                    onClick = { adding = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "手动记一笔", tint = Color.White)
                }
            }
        }
    }

    // 编辑
    editing?.let { tx ->
        TxEditDialog(
            initial = tx,
            categories = categories,
            onSave = { updated -> vm.updateTx(updated); editing = null },
            onDismiss = { editing = null },
        )
    }

    // 批量导入：点某一行补全/核对
    editingImport?.let { row ->
        TxEditDialog(
            initial = row.toEditableTransaction(),
            categories = categories,
            title = if (row.recognized) "核对识别结果" else "补全这一笔",
            onSave = { tx ->
                vm.updateImportRow(
                    id = row.id,
                    type = TxType.valueOf(tx.type),
                    amountCents = tx.amountCents,
                    categoryId = tx.categoryId,
                    merchant = tx.merchant,
                    note = tx.note,
                )
                editingImport = null
            },
            onDismiss = { editingImport = null },
        )
    }

    // 新增
    if (adding) {
        TxEditDialog(
            initial = null,
            categories = categories,
            onSave = { e ->
                vm.addTx(e.amountCents, TxType.valueOf(e.type), e.categoryId, e.merchant, e.note)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    // 删除确认
    pendingDelete?.let { tx ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这笔账？") },
            text = {
                val cat = catMap[tx.categoryId]
                Text(
                    "${cat?.name ?: "未知类别"} ¥${Money.fmt(tx.amountCents)}\n删了就没了，想清楚。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteTx(tx); pendingDelete = null }) {
                    Text("删除", color = ExpenseRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("留着") }
            }
        )
    }
}


@Composable
private fun MonthHeader(month: YearMonth, list: List<TransactionEntity>) {
    val income = list.filter { it.type == "INCOME" }.sumOf { it.amountCents }
    val expense = list.filter { it.type == "EXPENSE" }.sumOf { it.amountCents }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${month.year}年${month.monthValue}月",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text("收 ¥${Money.fmt(income)}", fontSize = 12.sp, color = IncomeGreen)
        Spacer(Modifier.size(10.dp))
        Text("支 ¥${Money.fmt(expense)}", fontSize = 12.sp, color = ExpenseRed)
    }
}

@Composable
private fun TxRow(
    tx: TransactionEntity,
    category: CategoryEntity?,
    onClick: () -> Unit,
) {
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val time = remember(tx.txTime) {
        Instant.ofEpochMilli(tx.txTime).atZone(ZoneId.systemDefault())
    }
    val isIncome = tx.type == "INCOME"
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Color((category?.color ?: 0xFFBDBDBDL).toInt()), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (category?.name ?: "无").take(1),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.merchant?.takeIf { it.isNotBlank() } ?: category?.name ?: "记账",
                fontSize = 15.sp,
                maxLines = 1
            )
            val sub = listOfNotNull(
                category?.name,
                tx.note?.takeIf { it.isNotBlank() && it != tx.merchant },
                time.format(fmt),
                if (tx.source == "CAPTURE") "截屏" else if (tx.source == "PHOTO") "相册" else "手动"
            ).joinToString(" · ")
            Text(sub, fontSize = 11.sp, color = TextGray, maxLines = 1)
        }
        Text(
            (if (isIncome) "+" else "-") + "¥" + Money.fmt(tx.amountCents),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isIncome) IncomeGreen else ExpenseRed
        )
    }
}

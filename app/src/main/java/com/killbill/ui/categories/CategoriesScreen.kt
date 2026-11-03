package com.killbill.ui.categories

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TxType
import com.killbill.ui.AppViewModel
import com.killbill.ui.theme.ExpenseRed
import com.killbill.ui.theme.TextGray

/** 自定义类别可选色板 */
private val PALETTE = listOf(
    0xFFE57373L, 0xFFF06292L, 0xFFBA68C8L, 0xFF9575CDL,
    0xFF7986CBL, 0xFF64B5F6L, 0xFF4FC3F7L, 0xFF4DD0E1L,
    0xFF4DB6ACL, 0xFF81C784L, 0xFFAED581L, 0xFFFFB74DL,
    0xFFFF8A65L, 0xFFA1887FL, 0xFF90A4AEL, 0xFFFFD54FL,
)

@Composable
fun CategoriesScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    val txCountByCat = remember(transactions) {
        transactions.groupingBy { it.categoryId }.eachCount()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf(TxType.EXPENSE to "支出类别", TxType.INCOME to "收入类别").forEach { (type, title) ->
                item(key = "sec_$type") {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                val cats = categories.filter { it.type == type.name }
                items(cats, key = { "cat_${it.id}" }) { c ->
                    CategoryRow(
                        cat = c,
                        txCount = txCountByCat[c.id] ?: 0,
                        canDelete = !c.isPreset,
                        onDelete = { pendingDelete = c },
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新增类别", tint = Color.White)
        }
    }

    if (showAdd) {
        AddCategoryDialog(
            onAdd = { name, type, color ->
                vm.addCategory(name, type, color)
                showAdd = false
            },
            onDismiss = { showAdd = false },
            existingNames = categories.map { it.type to it.name }.toSet(),
        )
    }

    pendingDelete?.let { c ->
        val count = txCountByCat[c.id] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除类别「${c.name}」？") },
            text = {
                Text(
                    if (count > 0) "该类别下 $count 笔交易将自动归入「其他」，账不会丢。"
                    else "该类别下没有交易，直接删。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteCategory(c); pendingDelete = null }) {
                    Text("删除", color = ExpenseRed)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CategoryRow(
    cat: CategoryEntity,
    txCount: Int,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color(cat.color.toInt()), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    cat.name.take(1),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cat.name, fontSize = 15.sp)
                Text(
                    if (cat.isPreset) "预置 · $txCount 笔" else "自定义 · $txCount 笔",
                    fontSize = 11.sp,
                    color = TextGray
                )
            }
            if (canDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "删除",
                    tint = TextGray,
                    modifier = Modifier.padding(4.dp).clickable(onClick = onDelete)
                )
            }
        }
    }
}

@Composable
private fun AddCategoryDialog(
    onAdd: (String, TxType, Long) -> Unit,
    onDismiss: () -> Unit,
    existingNames: Set<Pair<String, String>>,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxType.EXPENSE) }
    var color by remember { mutableStateOf(PALETTE[0]) }
    val dup = existingNames.contains(type.name to name.trim())
    val canAdd = name.trim().length in 1..6 && !dup

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增类别") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TxType.EXPENSE,
                        onClick = { type = TxType.EXPENSE },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = type == TxType.INCOME,
                        onClick = { type = TxType.INCOME },
                        label = { Text("收入") }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（1-6字）") },
                    singleLine = true,
                    isError = dup,
                    supportingText = if (dup) {
                        { Text("这个类别已经有了", color = ExpenseRed, fontSize = 12.sp) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("选个颜色", fontSize = 13.sp, color = TextGray)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.chunked(8).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                val sel = c == color
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .background(Color(c.toInt()), CircleShape)
                                        .then(
                                            if (sel) Modifier.padding(3.dp)
                                                .background(Color.White, CircleShape)
                                                .padding(3.dp)
                                                .background(Color(c.toInt()), CircleShape)
                                            else Modifier
                                        )
                                        .clickable { color = c }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canAdd) onAdd(name.trim(), type, color) }, enabled = canAdd) {
                Text("添加")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

package com.killbill.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.killbill.KillBillApp
import com.killbill.ai.ImageBillImporter
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TransactionEntity
import com.killbill.data.db.TxType
import com.killbill.stats.StatsRepository
import com.killbill.ui.transactions.BatchImportUiState
import com.killbill.ui.transactions.MAX_BATCH_IMPORT
import com.killbill.ui.transactions.buildImportRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val kapp = app as KillBillApp
    private val txDao = kapp.database.transactionDao()
    private val catDao = kapp.database.categoryDao()

    val categories: StateFlow<List<CategoryEntity>> = catDao.allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = txDao.allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var viewMonth by mutableStateOf(YearMonth.now())
        private set

    var stats by mutableStateOf(StatsRepository.Stats())
        private set

    /** 相册批量导入：确认页状态。识别/入库都在 ViewModel 里，旋转不丢 */
    var batchImport by mutableStateOf(BatchImportUiState())
        private set

    /** 批量导入完成后的 Toast 文案，UI 消费后清空 */
    var importResult by mutableStateOf<String?>(null)
        private set

    private var importJob: Job? = null
    private var importRowSeq = 0L

    init {
        viewModelScope.launch {
            combine(snapshotFlow { viewMonth }, transactions) { m, _ -> m }
                .collect { m ->
                    stats = withContext(Dispatchers.IO) { kapp.statsRepo.load(m) }
                }
        }
    }

    fun prevMonth() {
        viewMonth = viewMonth.minusMonths(1)
    }

    fun nextMonth() {
        if (viewMonth < YearMonth.now()) viewMonth = viewMonth.plusMonths(1)
    }

    fun deleteTx(tx: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) {
        kapp.billRepo.delete(tx)
    }

    fun updateTx(tx: TransactionEntity) = viewModelScope.launch(Dispatchers.IO) {
        kapp.billRepo.update(tx)
    }

    fun addTx(amountCents: Long, type: TxType, categoryId: Long, merchant: String?, note: String?) =
        viewModelScope.launch(Dispatchers.IO) {
            kapp.billRepo.insertManual(amountCents, type, categoryId, merchant, note)
        }

    // ---------- 相册批量导入 ----------

    /** 用户从相册选完图：逐张本地 OCR + 规则解析，边识别边把行推给确认页 */
    fun startImageImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val picked = uris.distinct().take(MAX_BATCH_IMPORT)
        importJob?.cancel()
        importRowSeq = 0
        batchImport = BatchImportUiState(
            visible = true,
            total = picked.size,
            analyzing = true,
        )
        importJob = viewModelScope.launch {
            picked.forEachIndexed { index, uri ->
                val outcome = ImageBillImporter.analyze(kapp, uri)
                if (!isActive) return@launch
                val type = outcome.draft?.type ?: TxType.EXPENSE
                val categoryId = kapp.billRepo.resolveCategory(outcome.draft?.categoryHint, type)
                val row = buildImportRow(++importRowSeq, uri, outcome, categoryId)
                batchImport = batchImport.copy(
                    rows = batchImport.rows + row,
                    done = index + 1,
                )
            }
            batchImport = batchImport.copy(analyzing = false)
        }
    }

    /** 关闭确认页；识别未完成时一并取消，绝不偷偷入库 */
    fun dismissImageImport() {
        if (batchImport.inserting) return
        importJob?.cancel()
        importJob = null
        batchImport = BatchImportUiState()
    }

    fun toggleImportRow(id: Long, included: Boolean) {
        batchImport = batchImport.copy(
            rows = batchImport.rows.map {
                if (it.id == id) it.copy(included = included) else it
            }
        )
    }

    /** 用户在确认页改完一笔：金额/类别补齐后自动勾回，省得再点一次 */
    fun updateImportRow(
        id: Long,
        type: TxType,
        amountCents: Long,
        categoryId: Long,
        merchant: String?,
        note: String?,
    ) {
        batchImport = batchImport.copy(
            rows = batchImport.rows.map { row ->
                if (row.id != id) row else row.copy(
                    type = type,
                    amountCents = amountCents,
                    categoryId = categoryId,
                    merchant = merchant,
                    note = note,
                    included = amountCents > 0 && categoryId > 0,
                )
            }
        )
    }

    /** 只入库勾选且金额/类别齐全的行；逐笔落库，失败不拖累其他笔 */
    fun confirmImageImport() {
        val state = batchImport
        if (!state.canConfirm) return
        val rows = state.rows.filter { it.included && it.readyForImport }
        batchImport = state.copy(inserting = true)
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            for (row in rows) {
                runCatching {
                    kapp.billRepo.insertImported(
                        amountCents = row.amountCents,
                        type = row.type,
                        categoryId = row.categoryId,
                        merchant = row.merchant,
                        note = row.note,
                        txTime = row.txTime,
                    )
                }.onSuccess { ok++ }.onFailure { failed++ }
            }
            importResult = when {
                failed == 0 -> "已从相册录入 $ok 笔"
                ok == 0 -> "录入失败，请重试"
                else -> "已录入 $ok 笔，$failed 笔失败"
            }
            batchImport = BatchImportUiState()
        }
    }

    fun clearImportResult() {
        importResult = null
    }

    fun addCategory(name: String, type: TxType, colorArgb: Long) =
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@launch
            if (catDao.findByName(trimmed, type.name) != null) return@launch
            catDao.insert(CategoryEntity(name = trimmed, type = type.name, color = colorArgb))
        }

    fun deleteCategory(cat: CategoryEntity) = viewModelScope.launch(Dispatchers.IO) {
        if (cat.isPreset) return@launch // 预置类别不许删
        // AC10：历史交易归"其他"
        val other = catDao.findByName("其他", cat.type)
        if (other != null && other.id != cat.id) {
            txDao.reassignCategory(cat.id, other.id)
        }
        catDao.delete(cat)
    }
}

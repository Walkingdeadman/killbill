package com.killbill.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.killbill.KillBillApp
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TransactionEntity
import com.killbill.data.db.TxType
import com.killbill.stats.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

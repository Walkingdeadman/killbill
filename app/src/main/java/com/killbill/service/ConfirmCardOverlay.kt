package com.killbill.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.killbill.KillBillApp
import com.killbill.R
import com.killbill.ai.BillDraft
import com.killbill.ai.BillParser
import com.killbill.data.db.CategoryEntity
import com.killbill.data.db.TxType
import com.killbill.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别结果确认卡片（AC5/AC6）。
 * 可改金额、切类别、切收支、放弃；确认回调一次性，防重复入库。
 */
class ConfirmCardOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val app: KillBillApp,
    private val draft: BillDraft,
    /** 图片来源标注："相册截屏 · xxx.png" / "实时截屏" */
    private val extraMeta: String? = null,
) {
    data class CardResult(
        val type: TxType,
        val amountCents: Long,
        val categoryId: Long,
        val merchant: String?,
        val note: String?,
        val txTime: Long?,
    )

    var onConfirm: ((CardResult) -> Unit)? = null
    /** 无论确认还是放弃，关闭时回调一次 */
    var onDone: (() -> Unit)? = null

    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private val view: View = LayoutInflater.from(context).inflate(R.layout.overlay_confirm_card, null)

    private val titleView: TextView = view.findViewById(R.id.cardTitle)
    private val amountInput: EditText = view.findViewById(R.id.amountInput)
    private val btnExpense: TextView = view.findViewById(R.id.btnExpense)
    private val btnIncome: TextView = view.findViewById(R.id.btnIncome)
    private val chipRow: LinearLayout = view.findViewById(R.id.chipRow)
    private val metaText: TextView = view.findViewById(R.id.metaText)
    private val btnCancel: TextView = view.findViewById(R.id.btnCancel)
    private val btnConfirm: TextView = view.findViewById(R.id.btnConfirm)

    private var type: TxType = draft.type
    private var categories: List<CategoryEntity> = emptyList()
    private var selectedCategoryId: Long = -1L
    private var attached = false
    private var submitted = false // AC6：一次性

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        // gravity=BOTTOM 时 y 即距底边偏移，避开 MarginLayoutParams 继承字段的解析问题
        y = (96 * context.resources.displayMetrics.density).toInt()
        val w = context.resources.displayMetrics.widthPixels
        width = (w * 0.9f).toInt()
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    fun show() {
        if (attached) return
        bindStatic()
        // 返回键关闭（窗口可聚焦时才收得到按键）
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else false
        }
        btnConfirm.setOnClickListener { submit() }
        btnCancel.setOnClickListener { dismiss() }
        btnExpense.setOnClickListener { switchType(TxType.EXPENSE) }
        btnIncome.setOnClickListener { switchType(TxType.INCOME) }

        wm.addView(view, params)
        attached = true
        refreshTypeUi()
        loadCategories()
    }

    fun dismiss() {
        if (!attached) return
        attached = false
        runCatching { wm.removeViewImmediate(view) }
        scope.coroutineContext[Job]?.cancel()
        onDone?.invoke()
    }

    private fun bindStatic() {
        val failReason = draft.failReason?.takeIf { it.isNotBlank() }
        titleView.text = when {
            failReason != null -> "没识别出来，手动记"
            !draft.merchant.isNullOrBlank() -> draft.merchant!!
            draft.amountUnknown -> "手动记一笔"
            else -> "识别结果"
        }
        if (!draft.amountUnknown) {
            amountInput.setText(Money.yuan(draft.amountCents))
        } else {
            amountInput.hint = if (failReason != null) "识别失败，请输入金额" else "0.00"
        }
        val metas = mutableListOf<String>()
        // 失败原因直接印在卡片上，不许静默失败
        failReason?.let { metas.add(it.take(90)) }
        extraMeta?.takeIf { it.isNotBlank() }?.let { metas.add(it) }
        draft.note?.takeIf { it.isNotBlank() }?.let { metas.add(it) }
        draft.categoryHint?.takeIf { it.isNotBlank() }?.let { metas.add("AI建议: $it") }
        draft.txTime?.let {
            metas.add(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it)))
        }
        metaText.text = metas.joinToString(" · ")
        metaText.visibility = if (metas.isEmpty()) View.GONE else View.VISIBLE
        if (failReason != null) {
            metaText.setTextColor(0xFFC62828.toInt())
        }
    }

    private fun switchType(t: TxType) {
        if (type == t) return
        type = t
        refreshTypeUi()
        loadCategories()
    }

    private fun refreshTypeUi() {
        btnExpense.setBackgroundResource(
            if (type == TxType.EXPENSE) R.drawable.bg_chip_sel_expense else R.drawable.bg_chip
        )
        btnIncome.setBackgroundResource(
            if (type == TxType.INCOME) R.drawable.bg_chip_sel_income else R.drawable.bg_chip
        )
        btnExpense.setTextColor(if (type == TxType.EXPENSE) 0xFFFFFFFF.toInt() else 0xFF444444.toInt())
        btnIncome.setTextColor(if (type == TxType.INCOME) 0xFFFFFFFF.toInt() else 0xFF444444.toInt())
    }

    private fun loadCategories() {
        scope.launch {
            val cats = withContext(Dispatchers.IO) { app.billRepo.categoriesFor(type) }
            categories = cats
            // 默认选中：hint 映射 > "其他" > 第一个
            val hint = draft.categoryHint
            selectedCategoryId = cats.firstOrNull { it.name == hint }?.id
                ?: cats.firstOrNull { hint != null && (it.name.contains(hint) || hint.contains(it.name)) }?.id
                ?: cats.firstOrNull { it.name == "其他" }?.id
                ?: cats.firstOrNull()?.id ?: -1L
            renderChips()
        }
    }

    private fun renderChips() {
        chipRow.removeAllViews()
        val dp8 = (8 * context.resources.displayMetrics.density).toInt()
        val dp4 = (4 * context.resources.displayMetrics.density).toInt()
        for (c in categories) {
            val chip = TextView(context).apply {
                text = c.name
                textSize = 13f
                setPadding(dp8 * 1 + dp4, dp4 + 2, dp8 + dp4, dp4 + 2)
                tag = c.id
                setOnClickListener {
                    selectedCategoryId = c.id
                    renderChips()
                }
            }
            val selected = c.id == selectedCategoryId
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_sel_neutral else R.drawable.bg_chip)
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF444444.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp4 * 2 }
            chipRow.addView(chip, lp)
        }
    }

    private fun submit() {
        if (submitted) return // AC6 防重复
        val amountText = amountInput.text.toString()
        val cents = BillParser.parseAmountToCents(amountText)
        if (cents == null || cents <= 0) {
            Toast.makeText(context, "金额不对劲，重新填", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedCategoryId < 0) {
            Toast.makeText(context, "类别还没加载好，稍等一秒", Toast.LENGTH_SHORT).show()
            return
        }
        submitted = true
        onConfirm?.invoke(
            CardResult(
                type = type,
                amountCents = cents,
                categoryId = selectedCategoryId,
                merchant = draft.merchant,
                note = draft.note,
                txTime = draft.txTime,
            )
        )
        dismiss()
    }
}

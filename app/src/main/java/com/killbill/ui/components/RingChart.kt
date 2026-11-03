package com.killbill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.sqrt

data class RingSlice(
    val id: Long,
    val label: String,
    val colorArgb: Long,
    val cents: Long,
)

/**
 * 环形占比图。点击扇区选中/取消（AC9），选中扇区全亮，其余降透明度。
 */
@Composable
fun RingChart(
    slices: List<RingSlice>,
    totalCents: Long,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    centerTitle: String = "总支出",
) {
    Canvas(
        modifier = modifier.pointerInput(slices, selectedId) {
            detectTapGestures { offset ->
                if (slices.isEmpty() || totalCents <= 0) {
                    onSelect(null)
                    return@detectTapGestures
                }
                val cx = size.width / 2f
                val cy = size.height / 2f
                val dx = offset.x - cx
                val dy = offset.y - cy
                val r = sqrt(dx * dx + dy * dy)
                val outer = minOf(size.width, size.height) / 2f
                val bandOuter = outer
                val bandInner = outer * 0.60f
                if (r < bandInner || r > bandOuter) {
                    onSelect(null)
                    return@detectTapGestures
                }
                // 12 点钟方向为 0 度，顺时针
                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
                if (angle < 0) angle += 360.0
                var acc = 0.0
                var picked: Long? = null
                for (s in slices) {
                    val sweep = s.cents.toDouble() / totalCents * 360.0
                    if (angle >= acc && angle < acc + sweep) {
                        picked = s.id
                        break
                    }
                    acc += sweep
                }
                onSelect(picked)
            }
        }
    ) {
        val stroke = size.minDimension * 0.20f
        val inset = stroke / 2f + 4f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeftX = inset
        val topLeftY = inset

        if (slices.isEmpty() || totalCents <= 0) {
            // 空态：灰色整环
            drawArc(
                color = Color(0xFFE0E0E0),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(topLeftX, topLeftY),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        } else {
            var start = -90f
            slices.forEach { s ->
                val sweep = (s.cents.toFloat() / totalCents * 360f).coerceAtLeast(0.5f)
                val dim = selectedId != null && selectedId != s.id
                val color = Color(s.colorArgb.toInt()).copy(alpha = if (dim) 0.35f else 1f)
                drawArc(
                    color = color,
                    startAngle = start + 0.8f,
                    sweepAngle = (sweep - 1.6f).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(topLeftX, topLeftY),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }

        // 中心文字
        val cx = size.width / 2f
        val cy = size.height / 2f
        val titlePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            this.color = 0xFF9E9E9E.toInt()
            textSize = size.minDimension * 0.085f
        }
        val amountPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            this.color = 0xFF212121.toInt()
            textSize = size.minDimension * 0.15f
            isFakeBoldText = true
        }
        val native = drawContext.canvas.nativeCanvas
        native.drawText(centerTitle, cx, cy - size.minDimension * 0.03f, titlePaint)
        native.drawText(
            com.killbill.util.Money.fmt(totalCents),
            cx, cy + size.minDimension * 0.13f, amountPaint
        )
    }
}

package com.killbill.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val KillBillGreen = Color(0xFF2E7D32)
val KillBillGreenDark = Color(0xFF1B5E20)
val KillBillGreenLight = Color(0xFFE8F5E9)
val ExpenseRed = Color(0xFFD32F2F)
val IncomeGreen = Color(0xFF2E7D32)
val UpRed = Color(0xFFC62828)
val DownGreen = Color(0xFF2E7D32)
val BgGray = Color(0xFFF6F7F6)
val TextGray = Color(0xFF757575)

private val LightColors = lightColorScheme(
    primary = KillBillGreen,
    onPrimary = Color.White,
    primaryContainer = KillBillGreenLight,
    onPrimaryContainer = KillBillGreenDark,
    secondary = Color(0xFF64B5F6),
    background = BgGray,
    surface = Color.White,
    error = ExpenseRed,
)

@Composable
fun KillBillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}

package com.killbill

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Android 15 (SDK 35) 启动冒烟——用户设备是 ColorOS Android 15，重点关照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivitySdk35Test {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `Android15上启动不闪退`() {
        rule.onNodeWithText("今日支出").assertIsDisplayed()
        rule.onNodeWithText("相较上月支出").assertIsDisplayed()
    }
}

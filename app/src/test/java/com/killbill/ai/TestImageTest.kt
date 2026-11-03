package com.killbill.ai

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连通性测试图守门测试。
 * MaaS 网关拒绝最小边长 <= 10px 的图（用户实测踩过坑），这张图不许改小。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestImageTest {

    @Test
    fun `测试图是合法PNG且边长大于10`() {
        val bytes = Base64.decode(QwenVisionClient.TINY_PNG_BASE64, Base64.DEFAULT)
        // PNG 魔数
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("不是 PNG", bytes.copyOfRange(0, 8).contentEquals(magic))
        // IHDR 宽高（大端，偏移 16/20）
        val w = readIntBE(bytes, 16)
        val h = readIntBE(bytes, 20)
        assertTrue("宽必须 > 10px，当前 $w", w > 10)
        assertTrue("高必须 > 10px，当前 $h", h > 10)
        assertEquals(32, w)
        assertEquals(32, h)
    }

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)
}

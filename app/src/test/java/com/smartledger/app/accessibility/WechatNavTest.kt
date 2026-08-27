package com.smartledger.app.accessibility

import com.smartledger.app.ocr.RapidOcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 微信视觉导航的 OCR 目标选择逻辑（纯函数）单测：
 * 截屏 → OCR 得到带坐标的文字行 → 从中挑出要点击的目标。
 */
class WechatNavTest {

    private fun line(text: String, cx: Int, cy: Int) = RapidOcrEngine.OcrLine(text, cx, cy)

    @Test
    fun pickBottomTabPrefersLowerHalf() {
        // 「我」是底部 tab：即使上半屏也有「我」字，也应选下半屏的那个
        val lines = listOf(
            line("我的小店", 200, 300),
            line("我", 945, 2280),
        )
        val hit = WechatImportCoordinator.pickTargetLine(lines, "我", preferBottom = true, 1080, 2376)
        assertEquals(945, hit?.cx)
        assertEquals(2280, hit?.cy)
    }

    @Test
    fun pickListRowPrefersUpperHalf() {
        // 「服务」是列表行：优先取上半屏命中
        val lines = listOf(
            line("服务", 200, 800),
            line("服务区公告", 900, 1500),
        )
        val hit = WechatImportCoordinator.pickTargetLine(lines, "服务", preferBottom = false, 1080, 2376)
        assertEquals(800, hit?.cy)
    }

    @Test
    fun exactMatchPreferredOverContains() {
        val lines = listOf(
            line("钱包服务中心", 300, 900),
            line("钱包", 400, 1000),
        )
        val hit = WechatImportCoordinator.pickTargetLine(lines, "钱包", preferBottom = false, 1080, 2376)
        assertEquals(1000, hit?.cy)
    }

    @Test
    fun fallsBackWhenPreferredRegionEmpty() {
        // 目标只在非偏好区域出现时，仍应返回（不能返回 null）
        val lines = listOf(
            line("我", 100, 300), // 上半屏，但偏好下半屏
        )
        val hit = WechatImportCoordinator.pickTargetLine(lines, "我", preferBottom = true, 1080, 2376)
        assertEquals(300, hit?.cy)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(WechatImportCoordinator.pickTargetLine(emptyList(), "账单", false, 1080, 2376))
        assertNull(WechatImportCoordinator.pickTargetLine(listOf(line("转账", 100, 100)), "账单", false, 1080, 2376))
    }

    @Test
    fun emptyTextLinesAreIgnored() {
        val lines = listOf(
            line("", 0, 0),
            line("  ", 100, 100),
            line("账单", 540, 300),
        )
        val hit = WechatImportCoordinator.pickTargetLine(lines, "账单", preferBottom = false, 1080, 2376)
        assertEquals(540, hit?.cx)
    }
}

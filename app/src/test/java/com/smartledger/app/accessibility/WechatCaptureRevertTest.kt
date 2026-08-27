package com.smartledger.app.accessibility

import com.smartledger.app.accessibility.WechatImportCoordinator.FrameAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 微信账单滚动截屏的「回退滑动」逻辑（纯函数）单测：
 * 复刻系统长截图——往回滑时缩短截图，不保留手滑滑过头多出来的部分。
 */
class WechatCaptureRevertTest {

    // 与 WechatImportCoordinator 的指纹尺寸一致：16 x 36
    private val w = 16
    private val h = 36

    /** 每行灰度唯一（行号 * 7 % 256），行间可区分，供位移估计定位 */
    private fun patternFp(): IntArray = IntArray(w * h) { i -> (i / w * 7) % 256 }

    /** 内容上移 k 行（往下滚、看更早账单）：b[r] = a[r+k]，越界填白 255 */
    private fun shiftUp(a: IntArray, k: Int): IntArray {
        val b = IntArray(a.size) { 255 }
        for (r in 0 until h) {
            val src = r + k
            if (src in 0 until h) {
                for (c in 0 until w) b[r * w + c] = a[src * w + c]
            }
        }
        return b
    }

    /** 内容下移 k 行（往回滚、回退）：b[r] = a[r-k]，越界填白 255 */
    private fun shiftDown(a: IntArray, k: Int): IntArray {
        val b = IntArray(a.size) { 255 }
        for (r in 0 until h) {
            val src = r - k
            if (src in 0 until h) {
                for (c in 0 until w) b[r * w + c] = a[src * w + c]
            }
        }
        return b
    }

    @Test
    fun verticalOffsetDetectsDirection() {
        val a = patternFp()
        assertEquals(5, WechatImportCoordinator.verticalOffset(a, shiftUp(a, 5)))
        assertEquals(-5, WechatImportCoordinator.verticalOffset(a, shiftDown(a, 5)))
        assertEquals(0, WechatImportCoordinator.verticalOffset(a, a))
    }

    @Test
    fun classifyEmptyIsAdvance() {
        val (action, _) = WechatImportCoordinator.classifyFrame(patternFp(), emptyList())
        assertEquals(FrameAction.ADVANCE, action)
    }

    @Test
    fun classifyStill() {
        val f0 = patternFp()
        val f1 = shiftUp(f0, 6)
        val saved = listOf(f0, f1)
        val (action, _) = WechatImportCoordinator.classifyFrame(f1, saved)
        assertEquals(FrameAction.STILL, action)
    }

    @Test
    fun classifyExactRevert() {
        // 回退到之前截过的 f1 位置：精确匹配 → 删掉 f1 之后的帧
        val f0 = patternFp()
        val f1 = shiftUp(f0, 6)
        val f2 = shiftUp(f0, 12)
        val saved = listOf(f0, f1, f2)
        val (action, idx) = WechatImportCoordinator.classifyFrame(shiftUp(f0, 6), saved)
        assertEquals(FrameAction.REVERT, action)
        assertEquals(1, idx)
    }

    @Test
    fun classifyMiddlePositionIsAdvanceNotRevert() {
        // 回退到两帧之间（不精确匹配任何历史帧）：不再触发方向回退，
        // 避免微信账单白底灰字导致的方向误判把正常下滑的帧删掉（应前进不删帧）
        val f0 = patternFp()
        val f1 = shiftUp(f0, 10)
        val f2 = shiftUp(f0, 20)
        val saved = listOf(f0, f1, f2)
        val (action, _) = WechatImportCoordinator.classifyFrame(shiftUp(f0, 15), saved)
        assertEquals(FrameAction.ADVANCE, action)
    }

    @Test
    fun classifyAdvance() {
        // 往下滚到 12 行（看更早账单）：前进，追加新帧
        val f0 = patternFp()
        val f1 = shiftUp(f0, 6)
        val saved = listOf(f0, f1)
        val (action, _) = WechatImportCoordinator.classifyFrame(shiftUp(f0, 12), saved)
        assertEquals(FrameAction.ADVANCE, action)
    }
}

package com.smartledger.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit 设备端 OCR 封装：
 * 优先使用中文识别模型，失败或空结果时回退到拉丁模型。
 *
 * 长截图支持：微信/支付宝账单长截图往往高达上万像素，若整图一次性送入 ML Kit，
 * 引擎会把小字压缩到无法识别的尺寸，导致大量漏字（例如 56 笔只扫出 30 笔）。
 * 因此这里把超长图片按纵向切成带重叠的条块，逐块识别后拼接，保证每块内文字清晰。
 *
 * 注意：ML Kit 依赖 Google Play 服务（GMS）。在无 GMS 的国产手机上
 * `TextRecognition.getClient` 会抛异常，因此这里全部用 runCatching 包裹 +
 * 懒加载，OCR 不可用时优雅降级（返回空文本），绝不导致崩溃。
 */
class OcrEngine(context: Context) {

    private val appContext = context.applicationContext

    private val chineseRecognizer: TextRecognizer? by lazy {
        runCatching { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            .getOrNull()
    }

    private val latinRecognizer: TextRecognizer? by lazy {
        runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            .getOrNull()
    }

    /** 离线 PaddleOCR 引擎（PP-OCRv4），精度更高，作为首选；不可用时回退 ML Kit */
    private val rapidOcr: RapidOcrEngine by lazy { RapidOcrEngine(appContext) }

    /** OCR 引擎是否可用（至少一个模型初始化成功） */
    val isAvailable: Boolean
        get() = chineseRecognizer != null || latinRecognizer != null || rapidOcr.isReady

    /** 识别图片中的全部文字；任何异常都返回空字符串，不抛出 */
    suspend fun recognize(uri: Uri): String = runOcr { decodeFrom { appContext.contentResolver.openInputStream(uri) } }

    /** 识别本地文件图片（微信自动导入的临时截屏）；任何异常都返回空字符串，不抛出 */
    suspend fun recognizeFile(path: String): String = runOcr { decodeFrom { java.io.File(path).inputStream() } }

    private suspend fun runOcr(load: () -> Bitmap): String = withContext(Dispatchers.IO) {
        val bitmap = runCatching { load() }.getOrNull() ?: return@withContext ""
        try {
            // 首选离线 PaddleOCR（分块 + 坐标去重）；空结果回退 ML Kit
            val rapidText = recognizeRapidTiled(bitmap)
            if (rapidText.isNotBlank()) rapidText else recognizeMlKitTiled(bitmap)
        } finally {
            // 任一 OCR 引擎/位图切片异常时也必须释放整图，避免连续导入时累积 OOM。
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 去掉空行与相邻重复行（分块重叠处可能把同一行识别两遍） */
    private fun dedupAdjacentLines(text: String): String {
        val result = mutableListOf<String>()
        for (raw in text.lines()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (result.lastOrNull() != t) result.add(t)
        }
        return result.joinToString("\n")
    }

    /**
     * RapidOCR 分块识别：高度超过阈值时切成带重叠的条块，逐块识别并把坐标换算成
     * 绝对坐标，再做跨分块去重（同一位置的框只保留一个）。
     * 识别前先做对比度拉伸：微信账单灰字对比度低，增强后明显提升识别率。
     */
    private fun recognizeRapidTiled(bitmap: Bitmap): String {
        val enhanced = runCatching { preprocess(bitmap) }.getOrNull() ?: bitmap
        val lines = collectRapidLines(enhanced)
        if (enhanced !== bitmap && !enhanced.isRecycled) enhanced.recycle()
        if (lines.isEmpty()) return ""
        val deduped = dedupRapidLines(lines)
        return deduped.joinToString("\n") { it.text }
    }

    /** 逐块收集 RapidOCR 识别行（把块内 y 坐标换算成整图绝对坐标） */
    private fun collectRapidLines(bitmap: Bitmap): List<RapidOcrEngine.OcrLine> {
        val width = bitmap.width
        val height = bitmap.height
        val out = mutableListOf<RapidOcrEngine.OcrLine>()
        var top = 0
        while (top < height) {
            val h = minOf(TILE_HEIGHT, height - top)
            val tile = Bitmap.createBitmap(bitmap, 0, top, width, h)
            val lines = runCatching { rapidOcr.recognize(tile) }.getOrNull().orEmpty()
            for (l in lines) out.add(l.copy(cy = l.cy + top))
            if (tile !== bitmap && !tile.isRecycled) tile.recycle()
            if (top + h >= height) break
            top += h - OVERLAP
        }
        return out
    }

    /**
     * 跨分块去重：
     *  1. 坐标接近（同一框被相邻分块各识别一遍）→ 只留一个；
     *  2. 同一行且文案相同（如「美团」图标与商家名各成框）→ 只留一个。
     */
    private fun dedupRapidLines(lines: List<RapidOcrEngine.OcrLine>): List<RapidOcrEngine.OcrLine> {
        val sorted = lines.sortedWith(compareBy({ it.cy }, { it.cx }))
        val kept = mutableListOf<RapidOcrEngine.OcrLine>()
        for (line in sorted) {
            // 已按 y 升序：距离 >= DEDUP_DIST 的更早行绝不可能重复，无需全量 O(n²) 扫描。
            var duplicate = false
            for (i in kept.lastIndex downTo 0) {
                val keptLine = kept[i]
                val dy = line.cy - keptLine.cy
                if (dy >= DEDUP_DIST) break
                val dx = kotlin.math.abs(keptLine.cx - line.cx)
                if ((dx < DEDUP_DIST && dy < DEDUP_DIST) ||
                    (dy < DEDUP_DIST && keptLine.text == line.text)
                ) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) kept.add(line)
        }
        return kept
    }

    /**
     * ML Kit 兜底路径：分块识别 + 预处理，相邻行去重。
     */
    private fun recognizeMlKitTiled(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val results = mutableListOf<String>()
        var top = 0
        while (top < height) {
            val h = minOf(TILE_HEIGHT, height - top)
            val tile = Bitmap.createBitmap(bitmap, 0, top, width, h)
            val text = recognizeMlKit(tile)
            if (text.isNotBlank()) results.add(text)
            if (tile !== bitmap && !tile.isRecycled) tile.recycle()
            if (top + h >= height) break
            top += h - OVERLAP
        }
        return dedupAdjacentLines(results.joinToString("\n"))
    }

    /** 单张位图 ML Kit 识别：预处理（灰度 + 对比度拉伸）后先中文后拉丁，空结果回退原图 */
    private fun recognizeMlKit(bitmap: Bitmap): String {
        val processed = runCatching { preprocess(bitmap) }.getOrNull() ?: bitmap
        val text = recognizeProcessed(processed)
        if (processed !== bitmap && !processed.isRecycled) processed.recycle()
        if (text.isNotBlank() || processed === bitmap) return text
        // 预处理后仍无结果：退回原图再识别一次（防极端低对比度图被拉伸过度）
        return recognizeProcessed(bitmap)
    }

    /** 对已预处理位图做实际识别：先中文后拉丁，空结果回退 */
    private fun recognizeProcessed(bitmap: Bitmap): String {
        chineseRecognizer?.let { recognizer ->
            val text = runCatching {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }

        latinRecognizer?.let { recognizer ->
            val text = runCatching {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }

        return ""
    }

    /**
     * 识别前预处理：转灰度 + 自适应对比度拉伸（2%–98% 分位数线性映射）。
     * 微信账单的金额、日期、退款注释多是发灰的小字，对比度低、笔画细，
     * ML Kit 容易把减号读成「一」、漏掉小数点/正负号、数字串位。
     * 拉伸后灰字压深近黑、背景提亮近白，符号与笔画更清晰，明显提升识别率；
     * 明/暗两种底色极性均保持不变，只增强对比。
     */
    private fun preprocess(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = IntArray(w * h)
        val hist = IntArray(256)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 加权灰度（Rec.601），与肉眼亮度一致
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            lum[i] = y
            hist[y]++
        }

        val lo = percentile(hist, 0.02f)
        val hi = percentile(hist, 0.98f)
        val range = (hi - lo).coerceAtLeast(1)

        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val y = (((lum[i] - lo) * 255f) / range).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 灰度直方图第 p 分位数（p 取 0.02 → 2%），用于剔除噪声决定拉伸上下限 */
    private fun percentile(hist: IntArray, p: Float): Int {
        val total = hist.sum()
        if (total <= 0) return 0
        var acc = 0
        val target = (total * p).toInt()
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return 255
    }

    /**
     * 解码图片（支持 content:// 与本地文件）：
     *  1. 保证短边（竖图的宽）至少 ~1000px，小字才可读——这是漏字的根因；
     *  2. 防止超大图 OOM，最终解码像素上限 ~24M（约 96MB ARGB）。
     */
    private fun decodeFrom(open: () -> java.io.InputStream?): Bitmap {
        // 第一步：只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val outW = bounds.outWidth
        val outH = bounds.outHeight
        if (outW <= 0 || outH <= 0) error("无法读取图片")

        val short = minOf(outW, outH)
        var sampleSize = 1
        // 短边保持在 ~1000px 附近：小字不糊，又不至于过大
        while (short / (sampleSize * 2) >= MIN_SHORT_SIDE) {
            sampleSize *= 2
        }
        // 超大图兜底：限制总像素，避免解码整图 OOM
        while (outW.toLong() * outH / (sampleSize.toLong() * sampleSize) > MAX_PIXELS) {
            sampleSize *= 2
        }

        // 第二步：按采样率真正解码
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("无法读取图片")
    }

    private companion object {
        /** 单块高度（最终像素），超出则分块 */
        const val TILE_HEIGHT = 2200

        /** 相邻块重叠像素，避免切断一行 */
        const val OVERLAP = 200

        /** 短边最小像素：低于此值小字会糊，导致漏字 */
        const val MIN_SHORT_SIDE = 1000

        /** 最终解码总像素上限（约 96MB ARGB_8888），防止超大图 OOM */
        const val MAX_PIXELS = 24_000_000L

        /** 去重判定距离（像素）：同一框/同一行内，坐标或文案在此距离内视为重复 */
        const val DEDUP_DIST = 15
    }
}

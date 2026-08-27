package com.smartledger.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 离线 PaddleOCR（PP-OCRv4）引擎：纯 Kotlin + ONNX Runtime，无 OpenCV、无 JNI。
 *
 * 流程：det（DBnet）检出文字框 → 按序裁剪 → rec（SVTR）逐框识别 → 贪心 CTC 解码。
 * 针对微信/支付宝账单等「水平文字」场景，检测后处理用「连通域 + 轴对齐框」替代
 * OpenCV 的 findContours + minAreaRect + warpPerspective，简化实现且足够准确。
 *
 * 输入归一化与 RapidOCR 官方一致：BGR 通道、(v/255 - 0.5) / 0.5（对三通道对称）。
 */
class RapidOcrEngine(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val detSession: OrtSession? by lazy { loadSession("ocr/det.onnx") }
    private val recSession: OrtSession? by lazy { loadSession("ocr/rec.onnx") }
    private val keys: List<String> by lazy { loadKeys() }

    /** 模型是否已成功加载（可用性判断） */
    val isReady: Boolean
        get() = detSession != null && recSession != null && keys.size > 2

    override fun close() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        runCatching { env.close() }
    }

    /** 对一张图（或分块后的一块）做完整识别，返回按阅读顺序排列、带坐标的行 */
    fun recognize(bitmap: Bitmap): List<OcrLine> {
        if (!isReady) {
            android.util.Log.w("RapidOCR", "not ready: det=${detSession != null} rec=${recSession != null} keys=${keys.size}")
            return emptyList()
        }
        if (bitmap.width < 8 || bitmap.height < 8) return emptyList()

        // ---------- det ----------
        val (pred, detW, detH) = detect(bitmap) ?: return emptyList()
        val boxes = extractBoxes(pred, detW, detH, bitmap.width, bitmap.height)
        if (boxes.isEmpty()) return emptyList()

        // ---------- rec（逐框识别） ----------
        val lines = mutableListOf<OcrLine>()
        for (box in boxes) {
            val crop = Bitmap.createBitmap(bitmap, box.x0, box.y0, box.w, box.h)
            val text = recognizeCrop(crop)
            if (!crop.isRecycled) crop.recycle()
            if (text.isNotBlank()) {
                lines.add(OcrLine(text, (box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2))
            }
        }
        android.util.Log.i("RapidOCR", "tile ${bitmap.width}x${bitmap.height} -> ${boxes.size} boxes, ${lines.size} lines")
        return lines
    }

    // ============ det ============

    // ONNX Runtime 的 OrtValue.value 静态类型为 Any，det 模型输出恒为 4 维 float 张量；
    // 已用 as? + null 兜底，转换失败安全返回 null，故抑制该处不可避免的 unchecked cast 告警。
    @Suppress("UNCHECKED_CAST")
    private fun detect(bitmap: Bitmap): Triple<FloatArray, Int, Int>? {
        val session = detSession ?: return null
        val (w, h) = detDims(bitmap.width, bitmap.height)
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            val buf = toBgrChw(resized)
            val inputName = session.inputNames.iterator().next()
            val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
            OnnxTensor.createTensor(env, buf, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result.get(0)
                    val info = out.info as? TensorInfo ?: return null
                    val outH = info.shape.getOrNull(2)?.toInt() ?: return null
                    val outW = info.shape.getOrNull(3)?.toInt() ?: return null
                    val arr = out.value as? Array<Array<Array<FloatArray>>> ?: return null
                    val pred = FloatArray(outH * outW)
                    var i = 0
                    for (y in 0 until outH) {
                        val row = arr[0][0][y]
                        for (x in 0 until outW) pred[i++] = row[x]
                    }
                    return Triple(pred, outW, outH)
                }
            }
        } finally {
            if (!resized.isRecycled) resized.recycle()
        }
    }

    /** det 输入尺寸：长边不超过 [LIMIT_SIDE]，并按 32 对齐 */
    private fun detDims(w: Int, h: Int): Pair<Int, Int> {
        var rw = w
        var rh = h
        val maxSide = maxOf(w, h)
        if (maxSide > LIMIT_SIDE) {
            val ratio = LIMIT_SIDE.toFloat() / maxSide
            rw = (w * ratio).toInt()
            rh = (h * ratio).toInt()
        }
        rw = ((rw / 32f).roundToInt() * 32).coerceAtLeast(32)
        rh = ((rh / 32f).roundToInt() * 32).coerceAtLeast(32)
        return rw to rh
    }

    /** 从概率图提取文字框（阈值 + 2x2 膨胀 + 连通域 + 轴对齐框 + 评分/外扩），返回原图坐标 */
    private fun extractBoxes(
        pred: FloatArray, detW: Int, detH: Int, origW: Int, origH: Int,
    ): List<TextBox> {
        val n = detW * detH

        // 二值化 + 2x2 膨胀
        val bin = BooleanArray(n)
        for (i in 0 until n) bin[i] = pred[i] > THRESH
        val dil = BooleanArray(n)
        for (y in 0 until detH - 1) {
            val rowBase = y * detW
            for (x in 0 until detW - 1) {
                val i = rowBase + x
                dil[i] = bin[i] || bin[i + 1] || bin[i + detW] || bin[i + detW + 1]
            }
        }

        // 8 连通域标记，得到每个分量的轴对齐框
        val visited = BooleanArray(n)
        val comps = ArrayList<IntArray>()
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (!dil[start] || visited[start]) continue
            var minX = detW; var minY = detH; var maxX = 0; var maxY = 0
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % detW
                val y = p / detW
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= detW || ny < 0 || ny >= detH) continue
                        val np = ny * detW + nx
                        if (dil[np] && !visited[np]) {
                            visited[np] = true
                            stack[sp++] = np
                        }
                    }
                }
            }
            comps.add(intArrayOf(minX, minY, maxX, maxY))
        }

        // 过滤 + 评分 + 外扩 + 映射回原图
        val boxes = ArrayList<TextBox>()
        for (c in comps) {
            val cw = c[2] - c[0] + 1
            val ch = c[3] - c[1] + 1
            if (cw < MIN_SIDE || ch < MIN_SIDE) continue

            val score = boxScore(pred, detW, c[0], c[1], c[2], c[3])
            if (score < BOX_THRESH) continue

            // unclip：按面积/周长 * 倍率 向四周外扩
            val area = cw * ch
            val perimeter = 2 * (cw + ch)
            val dist = (area * UNCLIP_RATIO / perimeter).toInt().coerceAtLeast(1)
            val nx0 = (c[0] - dist).coerceIn(0, detW - 1)
            val ny0 = (c[1] - dist).coerceIn(0, detH - 1)
            val nx1 = (c[2] + dist).coerceIn(0, detW - 1)
            val ny1 = (c[3] + dist).coerceIn(0, detH - 1)
            if (nx1 - nx0 + 1 < MIN_SIDE + 2 || ny1 - ny0 + 1 < MIN_SIDE + 2) continue

            // 映射回原图坐标
            val x0 = (nx0.toFloat() / detW * origW).roundToInt().coerceIn(0, origW - 1)
            val y0 = (ny0.toFloat() / detH * origH).roundToInt().coerceIn(0, origH - 1)
            val x1 = (nx1.toFloat() / detW * origW).roundToInt().coerceIn(0, origW - 1)
            val y1 = (ny1.toFloat() / detH * origH).roundToInt().coerceIn(0, origH - 1)
            if (x1 - x0 + 1 < 3 || y1 - y0 + 1 < 3) continue
            boxes.add(TextBox(x0, y0, x1, y1, score))
        }

        // 按阅读顺序排序：先按 y 自上而下，同一行（y 差 < 10）内按 x 自左而右
        boxes.sortWith { a, b ->
            if (kotlin.math.abs(a.y0 - b.y0) < Y_THRESH) a.x0 - b.x0 else a.y0 - b.y0
        }
        return boxes
    }

    /** 框内概率均值（fast 模式，用轴对齐矩形近似） */
    private fun boxScore(pred: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var sum = 0.0
        var cnt = 0
        for (y in y0..y1) {
            val base = y * w
            for (x in x0..x1) {
                sum += pred[base + x]
                cnt++
            }
        }
        return if (cnt == 0) 0f else (sum / cnt).toFloat()
    }

    // ============ rec ============

    // 同 detect：rec 模型输出恒为 3 维 float 张量，as? + null 兜底安全，抑制 unchecked cast 告警。
    @Suppress("UNCHECKED_CAST")
    private fun recognizeCrop(crop: Bitmap): String {
        val session = recSession ?: return ""
        val ratio = crop.width.toFloat() / crop.height.toFloat()
        val resizedW = ceil(REC_HEIGHT * ratio).toInt().coerceAtLeast(1)
        val imgW = maxOf(REC_MAX_WIDTH, resizedW)
        val resized = Bitmap.createScaledBitmap(crop, resizedW, REC_HEIGHT, true)
        try {
            val buf = toBgrChwPadded(resized, imgW)
            val inputName = session.inputNames.iterator().next()
            val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), imgW.toLong())
            OnnxTensor.createTensor(env, buf, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result.get(0)
                    val arr = out.value as? Array<Array<FloatArray>> ?: return ""
                    return decode(arr[0])
                }
            }
        } finally {
            if (!resized.isRecycled) resized.recycle()
        }
    }

    /** 贪心 CTC 解码：去重、去 blank(index 0) */
    private fun decode(rows: Array<FloatArray>): String {
        val sb = StringBuilder()
        var lastIndex = 0
        for (row in rows) {
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (i in row.indices) {
                if (row[i] > maxVal) {
                    maxVal = row[i]
                    maxIdx = i
                }
            }
            if (maxIdx in 1 until keys.size && maxIdx != lastIndex) {
                sb.append(keys[maxIdx])
            }
            lastIndex = maxIdx
        }
        return sb.toString()
    }

    // ============ 通用 ============

    private fun loadSession(name: String): OrtSession? = try {
        val bytes = appContext.assets.open(name).use { it.readBytes() }
        val session = env.createSession(bytes)
        android.util.Log.i("RapidOCR", "loaded model: $name (${bytes.size} bytes)")
        session
    } catch (t: Throwable) {
        android.util.Log.e("RapidOCR", "load model $name failed: ${t.message}", t)
        null
    }

    private fun loadKeys(): List<String> = runCatching {
        val lines = appContext.assets.open("ocr/keys.txt").bufferedReader()
            .lineSequence()
            .filter { it.isNotEmpty() }
            .toMutableList()
        lines.add(0, "#")   // blank 占位
        lines.add(" ")      // 空格
        lines.toList()
    }.getOrElse { emptyList() }

    /** BGR 通道 + (v/255-0.5)/0.5 归一化 → CHW FloatBuffer */
    private fun toBgrChw(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val size = w * h
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(size * 3)
        // B 平面
        for (i in 0 until size) buf.put((pixels[i] and 0xFF) / 127.5f - 1f)
        // G 平面
        for (i in 0 until size) buf.put(((pixels[i] shr 8) and 0xFF) / 127.5f - 1f)
        // R 平面
        for (i in 0 until size) buf.put(((pixels[i] shr 16) and 0xFF) / 127.5f - 1f)
        buf.rewind()
        return buf
    }

    /** 同 [toBgrChw]，但右侧零填充到 [imgW] 宽度 */
    private fun toBgrChwPadded(bitmap: Bitmap, imgW: Int): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(imgW * h * 3)
        for (c in 0 until 3) {
            for (y in 0 until h) {
                val rowBase = y * w
                for (x in 0 until imgW) {
                    if (x < w) {
                        val p = pixels[rowBase + x]
                        val v = when (c) {
                            0 -> (p and 0xFF) / 127.5f - 1f
                            1 -> ((p shr 8) and 0xFF) / 127.5f - 1f
                            else -> ((p shr 16) and 0xFF) / 127.5f - 1f
                        }
                        buf.put(v)
                    } else {
                        buf.put(0f)
                    }
                }
            }
        }
        buf.rewind()
        return buf
    }

    private data class TextBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float) {
        val w: Int get() = x1 - x0 + 1
        val h: Int get() = y1 - y0 + 1
    }

    /** 一行识别结果，带文字框中心坐标（输入图坐标，用于跨分块去重） */
    data class OcrLine(val text: String, val cx: Int, val cy: Int)

    private companion object {
        /** det 输入长边上限（像素），超出则等比缩小 */
        const val LIMIT_SIDE = 2000

        /** det 二值化阈值（概率 > 0.3 视为文字） */
        const val THRESH = 0.3f

        /** 文字框平均置信度门槛 */
        const val BOX_THRESH = 0.5f

        /** unclip 外扩倍率 */
        const val UNCLIP_RATIO = 1.6f

        /** 文字框最小边长（像素，det 输出空间） */
        const val MIN_SIDE = 3

        /** 同行判定：y 坐标差小于此值视为同一行 */
        const val Y_THRESH = 10

        /** rec 输入高度与最大宽度 */
        const val REC_HEIGHT = 48
        const val REC_MAX_WIDTH = 320
    }
}

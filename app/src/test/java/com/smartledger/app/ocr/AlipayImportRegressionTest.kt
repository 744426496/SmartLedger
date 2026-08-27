package com.smartledger.app.ocr

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付宝识别"乱/多"回归测试（方案A+B）：
 *   B. 支付宝走交替布局（当前商家跟踪+金额配对），不做「金额[i]↔商家[i]」序号硬配对，
 *      避免 OCR 漏读/多读一行导致整体错位（实测「分账-某平台=730」这种金额配错商家）；
 *   A. 跨帧去重放宽到"同一自然日"，同一笔在不同帧读出的日期分/秒略有出入也能合并。
 * 根因说明：单帧解析本身正确（支付宝顶部汇总/混合行都能对），问题集中在——列布局错位、
 * 状态词被误读成商家（「之麻先种下半成功」）、跨帧去重按「同分钟」过严漏合。
 */
class AlipayImportRegressionTest {

    // 支付宝账单 OCR fixture（模拟账单结构：顶部汇总卡片/平台标识/分类标签/状态词/7月下限），28 笔。
    // 与 AlipayTopSummaryRegressionTest 用同一份数据，但这里强制 alipay=true 走支付宝专用解析。
    // 数据为虚构示例，保留结构、金额与日期用于回归。
    private val realOcrText = javaClass.classLoader!!
        .getResourceAsStream("alipay_bill_sample.txt")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun alipayRealBillYields28OnDedicatedParser() {
        val photoTakenAt = LocalDateTime.of(2026, 8, 17, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val items = ReceiptParser.parseLineItems(realOcrText, "expense", photoTakenAt, alipay = true)

        assertEquals("支付宝专用解析应为 28 笔", 28, items.size)
        assertEquals("交易关闭 7 笔", 7, items.count { it.closed })
        assertEquals("收入 2 笔（+100 / +730）", 2, items.count { it.type == "income" })
        assertEquals("0.00 押金类 5 笔", 5, items.count { it.amount == 0.0 })

        // 内部转移（总资产不变）：余额提现 / 银行卡定时转入 / 余额宝转出
        assertTrue("银行卡定时转入应为内部转移", items.first { it.name == "银行卡定时转入" }.internal)
        assertTrue("余额提现应为内部转移", items.first { it.amount == 34.67 }.internal)
        assertTrue("余额宝-转出到银行卡应为内部转移", items.first { it.amount == 0.36 }.internal)
        assertFalse("某某餐厅不是内部转移", items.first { it.amount == 29.58 }.internal)
    }

    @Test
    fun alipayStatusWordMisreadNotBecomeMerchant() {
        // OCR 把「芝麻免押下单成功」误读成「之麻先种下半成功」：不得当成商家名
        val text = """
            守约完成解冻押金
            0.00
            信用借还
            之麻先种下半成功
            08-1410:01
            收款-某某商家
            -680.00
            转账红包
            08-1117:41
        """.trimIndent()
        val photoTakenAt = LocalDateTime.of(2026, 8, 17, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val items = ReceiptParser.parseLineItems(text, "expense", photoTakenAt, alipay = true)

        // 不应出现以「之麻先种下半成功」为名、金额 680 的错配条目
        assertFalse("状态词误读不得成为商家名", items.any { it.name.contains("之麻") })
        // 条目「收款-某某商家 680」应存在
        val skip = items.firstOrNull { it.name.contains("收款") }
        assertEquals(680.0, skip?.amount ?: 0.0, 0.001)
        // 「守约完成解冻押金 0.00」保留
        assertEquals(2, items.size)
    }

    @Test
    fun alipayStatusDanglingDoesNotPairNextAmount() {
        // 状态词误读行后紧跟金额、且无商家行（极端丢行）：状态行被滤掉后，
        // 金额不会与「守约完成解冻押金」错配成 680，也不会产生「之麻」条目
        val text = """
            守约完成解冻押金
            0.00
            信用借还
            之麻先种下半成功
            -680.00
            转账红包
            08-1117:41
        """.trimIndent()
        val photoTakenAt = LocalDateTime.of(2026, 8, 17, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val items = ReceiptParser.parseLineItems(text, "expense", photoTakenAt, alipay = true)
        assertFalse("不得出现「之麻」误码商家", items.any { it.name.contains("之麻") })
        assertFalse("金额不得错配到「守约完成解冻押金」", items.any { it.name.contains("守约") && it.amount == 680.0 })
    }

    @Test
    fun alipayBottomTransactionFromDeviceDumpIsParseable() {
        // 模拟 1080x2376 截图的手机 OCR dump：原先截图阶段裁掉底部 14%，
        // 因而第五笔「某某退货-寄件费 -6.00」根本没有进入这里。裁剪修复后，
        // 这份完整文本必须解析为五笔，证明问题不在支付宝解析器。
        val text = """
            我的消费图鉴>
            8
            月
            今年累计已省
            支出
            收入
            ￥80
            ￥ 830.00
            ￥ 1,310.13
            收支分析
            本月已省0.57元>
            某某零食店
            -25.18
            餐饮美食
            今天17:08
            订单0000000000
            -9.98
            家居家装
            今天13:12
            银行卡定时转入
            50.00
            投资理财
            今天09:50
            退款-已接某某智能插座WiFi手...
            37.90
            退
            退款
            08-2212:41
            某某退货-寄件费0000000000000_L...
            -6.00
            生活服务
            08-2212:41
        """.trimIndent()
        val photoTakenAt = LocalDateTime.of(2026, 8, 22, 20, 48)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val items = ReceiptParser.parseLineItems(text, "expense", photoTakenAt, alipay = true)

        assertEquals("底部完整交易也应被解析，实际=$items", 5, items.size)
        assertTrue("某某退货寄件费应保留", items.any { it.name.contains("某某退货") && it.amount == 6.0 })
        assertTrue("某某智能退款应保留", items.any { it.name.contains("某某智能") && it.amount == 37.9 && it.refunded })
    }

    @Test
    fun alipayOvershootPreviousMonthIsDropped() {
        // 用户目标为「当月」8 月，但滑过头滚到 7 月截到更早账目（实测某次 74 笔被识别成 76 笔）。
        // 用户滚进 7 月那屏带出「2026年7月」月界 + 7 月账目（某某竞拍平台 -2.90 7月31日），
        // 解析后这些更早月份的条目必须被剔除，只保留 8 月目标账目。
        val text = """
            2026年
            2939.82
            抖音电商商家
            -35.22
            8月6日01:22
            美团
            -50.00
            美团
            8月5日19:51
            转账-来自某同事
            +300.00
            8月5日18:58
            哗哩哗哩弹幕网
            -3.00
            8月4日02:02
            扫描二维码付款-给某某百货
            -0.50
            谷盛百
            8月1日19:29
            2026年7月√
            支出￥1930.70收入￥1844.06
            某某竞拍平台
            -2.90
            BJFF
            7月31日23:53
        """.trimIndent()
        val photoTakenAt = LocalDateTime.of(2026, 8, 24, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val items = ReceiptParser.parseLineItems(text, "expense", photoTakenAt, alipay = true)
        val filtered = ReceiptParser.dropOvershoot(items)

        // 目标 8 月账目全部保留（5 笔：抖音电商/美团/转账来自某同事/哔哩哔哩/某某百货）
        assertTrue("8月抖音电商应保留", filtered.any { it.name.contains("抖音电商") && it.amount == 35.22 })
        assertTrue("8月美团应保留", filtered.any { it.name.contains("美团") && it.amount == 50.0 })
        assertTrue("8月转账来自某同事应保留", filtered.any { it.name.contains("某同事") && it.amount == 300.0 })
        assertTrue("8月哔哩哔哩应保留", filtered.any { (it.name.contains("哔哩哔哩") || it.name.contains("哗哩哗哩")) && it.amount == 3.0 })
        assertTrue("8月某某百货应保留", filtered.any { it.amount == 0.5 })
        // 7 月误入（某某竞拍平台 2.90 7月31日）必须被剔除
        assertFalse("滑过头滚进7月的账目应剔除", filtered.any { it.name.contains("某某竞拍") })
        assertFalse("7月31日账目不应保留", filtered.any { it.timestamp != null &&
            java.time.Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).monthValue == 7 })
        assertEquals(5, filtered.size)
    }

    @Test
    fun alipaySameMonthFullyKept() {
        // 同月账目（含月初8月6、月末8月3）全部保留，不再按停点日微调（修复「识别少了」）
        val items = listOf(
            LineItem("美团", 7.01, "餐饮", type = "expense", timestamp = tsAt(2026, 8, 6, 10, 0)),
            LineItem("中国电信", 29.94, "生活", type = "expense", timestamp = tsAt(2026, 8, 5, 9, 0)),
            LineItem("京东", 55.0, "购物", type = "expense", timestamp = tsAt(2026, 8, 3, 12, 0)),
        )
        assertEquals("同月账目全部保留", 3, ReceiptParser.dropOvershoot(items).size)
    }

    private fun tsAt(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun alipayDropOvershootDropsJulyEvenWhenLastFrameIsJuly() {
        // 关键失败模式修复：用户滑过头滚进 7 月，且「最后一帧」本身就是 7 月（回退未触发/停下在7月）。
        // 旧逻辑用「最后一帧最早日期」当停点 → 停点=7月日期 → 7月误入被保留（导致「76条+月日账单」）。
        // 新逻辑：目标月份永远取「最新账目所在月」（8月），7月整组剔除，即便最后一帧是7月。
        val photoTakenAt = LocalDateTime.of(2026, 8, 24, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val frameAug = """
            2026年
            2939.82
            美团
            -12.60
            美团
            8月3日23:06
            扫二维码付款-给某某百货
            -0.50
            8月1日19:29
        """.trimIndent()
        val frameJuly = """
            2026年7月√
            支出￥1930.70收入￥1844.06
            某某竞拍平台
            -2.90
            7月31日23:53
            拼团平台
            -45.90
            7月30日18:00
        """.trimIndent()
        val allItems = mutableListOf<LineItem>()
        val frameItems = mutableListOf<List<LineItem>>()
        for (f in listOf(frameAug, frameJuly)) {
            val items = ReceiptParser.parseLineItems(f, "expense", photoTakenAt, alipay = true)
            allItems.addAll(items)
            frameItems.add(items)
        }
        // 最后一帧是 7 月 → 停点提示为 7 月日期（旧逻辑的致命缺陷）
        val settleHint = frameItems.lastOrNull()?.mapNotNull { it.timestamp }?.minOrNull()
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        assertEquals("最后一帧为7月，停点提示应为7月日期", 7, settleHint?.monthValue)

        val deduped = ReceiptParser.dedupeOverlapping(allItems, alipay = true)
        val filtered = ReceiptParser.dropOvershoot(deduped)

        assertFalse("7月某某竞拍必须被剔除", filtered.any { it.name.contains("某某竞拍") })
        assertFalse("7月拼团平台必须被剔除", filtered.any { it.name.contains("拼团") })
        assertTrue("8月账目保留", filtered.any { it.amount == 12.60 || it.amount == 0.5 })
        assertEquals("仅保留8月目标账目", 2, filtered.size)
    }

    @Test
    fun alipayDropOvershootSameMonthKept() {
        // 同月账目全部保留（含月初8月6、月末8月3），只做跨月剔除，不做同月日级截断
        val items = listOf(
            LineItem("美团", 7.01, "餐饮", type = "expense", timestamp = tsAt(2026, 8, 6, 10, 0)),
            LineItem("中国电信", 29.94, "生活", type = "expense", timestamp = tsAt(2026, 8, 5, 9, 0)),
            LineItem("京东", 55.0, "购物", type = "expense", timestamp = tsAt(2026, 8, 3, 12, 0)),
        )
        val filtered = ReceiptParser.dropOvershoot(items)
        assertEquals("同月账目全部保留", 3, filtered.size)
        assertTrue(filtered.any { it.name == "美团" })
        assertTrue(filtered.any { it.name == "中国电信" })
        assertTrue(filtered.any { it.name == "京东" })
    }

    @Test
    fun alipayDedupMergesSameDayDifferentMinute() {
        val tz = ZoneId.systemDefault()
        val day = LocalDateTime.of(2026, 8, 11, 15, 48).atZone(tz).toInstant().toEpochMilli()
        // 同一笔在相邻滚动帧读出的分不同（仍同一天）：支付宝模式应合并
        val sameDayDiffMinute = day + 4 * 60_000L
        val dup = listOf(
            LineItem("分账-基础软件服务费", 4.38, "商业服务", type = "expense", timestamp = day),
            LineItem("分账-基础软件服务费", 4.38, "商业服务", type = "expense", timestamp = sameDayDiffMinute),
        )
        assertEquals("支付宝跨帧：同一天不同分钟应合并", 1, ReceiptParser.dedupeOverlapping(dup, alipay = true).size)

        // 微信模式（默认）：同一天不同分钟仍是两笔（精确到分钟）
        assertEquals("微信模式保持同分钟判重", 2, ReceiptParser.dedupeOverlapping(dup).size)

        // 不同天同名同额：不是同一笔，即便支付宝模式也不合并
        val nextDay = day + 86_400_000L
        val diffDay = listOf(
            LineItem("银行卡定时转入", 25.0, "投资理财", type = "expense", timestamp = day),
            LineItem("银行卡定时转入", 25.0, "投资理财", type = "expense", timestamp = nextDay),
        )
        assertEquals("不同天同名同额不合并", 2, ReceiptParser.dedupeOverlapping(diffDay, alipay = true).size)
    }

    @Test
    fun dropUntimedGhostsKeepsSameNameDifferentAmount() {
        // 根因回归：「余额提现 34.67」（有时间戳）与「余额提现 31.92」（无时间戳，最后一帧日期被截断）
        // 是两笔独立账单。旧的 dropUntimedGhosts 只按名字判「幽灵」，会把 31.92 误删，导致「识别少了」。
        // 修复后：只有「同名且金额一致」才判为截断版删除；同名不同金额保留。
        val ts = tsAt(2026, 8, 11, 19, 9)
        val items = listOf(
            LineItem("余额提现", 34.67, "其他", type = "expense", timestamp = ts),
            LineItem("余额提现", 31.92, "其他", type = "expense", timestamp = null),
        )
        val kept = ReceiptParser.dropUntimedGhosts(items)
        assertEquals("同名不同金额的无时间戳账单应保留", 2, kept.size)
        assertTrue("31.92 应保留", kept.any { it.amount == 31.92 })
    }

    @Test
    fun dropUntimedGhostsDropsSameNameSameAmountUntimed() {
        // 同名同额且无时间戳 → 是有时间戳真身的截断版，应丢弃（原行为保留）
        val ts = tsAt(2026, 8, 11, 15, 48)
        val items = listOf(
            LineItem("分账-基础软件服务费", 4.38, "商业服务", type = "expense", timestamp = ts),
            LineItem("分账-基础软件服务费", 4.38, "商业服务", type = "expense", timestamp = null),
        )
        val kept = ReceiptParser.dropUntimedGhosts(items)
        assertEquals("同名同额无时间戳判为截断版丢弃", 1, kept.size)
    }

    @Test
    fun dropUntimedGhostsMergesOcrSplitAmount() {
        // OCR 把「34.67」拆成「34」+「167」读成 34.0：与真身 34.67 差 0.67（<1 元），
        // 应判同一笔截断版丢弃；而另一笔「31.92」（差 2.75）是独立账单，应保留。
        val ts = tsAt(2026, 8, 11, 19, 9)
        val items = listOf(
            LineItem("余额提现", 34.67, "其他", type = "expense", timestamp = ts),
            LineItem("余额提现", 34.0, "其他", type = "expense", timestamp = null),
            LineItem("余额提现", 31.92, "其他", type = "expense", timestamp = null),
        )
        val kept = ReceiptParser.dropUntimedGhosts(items)
        assertEquals("34.0 判为 34.67 的截断版丢弃，31.92 保留，共 2 笔", 2, kept.size)
        assertTrue("31.92 应保留", kept.any { it.amount == 31.92 })
        assertFalse("34.0 截断版应丢弃", kept.any { it.amount == 34.0 })
    }
}

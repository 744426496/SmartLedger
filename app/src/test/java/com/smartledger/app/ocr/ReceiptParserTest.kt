package com.smartledger.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReceiptParserTest {

    @Test
    fun parseRestaurantReceipt() {
        val text = """
            海底捞火锅(市中心店)
            单号：20241231001
            合计：¥268.50
            支付时间：2024-12-31 18:30
            谢谢惠顾
        """.trimIndent()

        val info = ReceiptParser.parse(text)

        assertEquals("expense", info.type)
        assertEquals(268.5, info.amount!!, 0.001)
        assertEquals("海底捞火锅(市中心店)", info.merchant)
        assertEquals("餐饮", info.category)
        val expected = LocalDateTime.of(2024, 12, 31, 18, 30)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, info.timestamp)
    }

    @Test
    fun parseWechatPaymentReceipt() {
        val text = """
            微信支付凭证
            付款方：李四
            收款方：沙县小吃
            金额：¥18.00
            支付时间：2025-01-15 12:20
        """.trimIndent()

        val info = ReceiptParser.parse(text)

        assertEquals("expense", info.type)
        assertEquals(18.0, info.amount!!, 0.001)
        assertEquals("沙县小吃", info.merchant)
        assertEquals("餐饮", info.category)
    }

    @Test
    fun parseSalaryIncome() {
        val text = """
            招商银行
            工资入账通知
            收款方：北京某某科技有限公司
            入账金额：8,500.00
            日期：2025-01-10
        """.trimIndent()

        val info = ReceiptParser.parse(text)

        assertEquals("income", info.type)
        assertEquals(8500.0, info.amount!!, 0.001)
        assertEquals("工资", info.category)
        assertNotNull(info.merchant)
    }

    @Test
    fun amountPrefersDecimalOverYear() {
        // 2024 是年份，不应被当成金额
        val info = ReceiptParser.parse("2024-12-31 消费记录 68.50")
        assertEquals(68.5, info.amount!!, 0.001)
    }

    @Test
    fun amountKeywordLineWins() {
        // "单价" 行的 3.5 不应盖过 "合计" 行的 120.00
        val text = "矿泉水 单价3.5 数量2 合计：¥120.00"
        val info = ReceiptParser.parse(text)
        assertEquals(120.0, info.amount!!, 0.001)
    }

    @Test
    fun emptyTextYieldsNullAmount() {
        val info = ReceiptParser.parse("")
        assertNull(info.amount)
        assertNull(info.merchant)
    }

    @Test
    fun timestampFallsBackToPhotoTime() {
        val photoTime = LocalDateTime.of(2025, 2, 3, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val info = ReceiptParser.parse("某商店 合计 ¥10.00", photoTakenAt = photoTime)
        assertEquals(photoTime, info.timestamp)
    }

    // ---------- 多行明细 ----------

    @Test
    fun parseLineItemsFromReceipt() {
        val text = """
            农夫山泉 550ml    2.00
            乐事薯片          8.50
            双汇火腿肠        12.00
            合计              22.50
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(3, items.size)
        assertEquals("农夫山泉", items[0].name)
        assertEquals(2.0, items[0].amount, 0.001)
        assertEquals("乐事薯片", items[1].name)
        assertEquals(8.5, items[1].amount, 0.001)
        assertEquals("双汇火腿肠", items[2].name)
    }

    @Test
    fun parseLineItemsSignedWechatBill() {
        val text = """
            美团 -7.01
            中国电信 -29.94
            转账 - 来自亲友 +50.00
            拼团平台商户 - 退款 +7.90
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(4, items.size)
        assertEquals("美团", items[0].name)
        assertEquals(7.01, items[0].amount, 0.001)
        assertEquals("expense", items[0].type)
        assertEquals(29.94, items[1].amount, 0.001)
        assertEquals("expense", items[1].type)
        assertEquals(50.0, items[2].amount, 0.001)
        assertEquals("income", items[2].type)
        assertEquals(7.9, items[3].amount, 0.001)
        assertEquals("income", items[3].type)
    }

    @Test
    fun parseLineItemsSkipsSummaryAndMeta() {
        val text = """
            外卖订单
            黄焖鸡米饭 25.00
            冰红茶 3.00
            配送费 5.00
            合计 33.00
            支付时间 2025-01-01 12:00
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(3, items.size)
        assertEquals("黄焖鸡米饭", items[0].name)
        assertEquals("冰红茶", items[1].name)
        assertEquals("配送费", items[2].name)
    }

    @Test
    fun parseLineItemsStripsIconNoiseChars() {
        // 模拟 ML Kit 把微信账单左侧图标误识别为 "门" 等噪声字（紧贴/行首行尾）
        val text = """
            门商家转账-来自拼团平台
            美团门
            门美团
            中国电信
            抖音-手机充值
            转账-来自亲友门
            家常餐厅
            拼团平台商户-退款
            +1.80
            -7.01
            -5.00
            -29.94
            -10.00
            +50.00
            -19.00
            +7.90
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(8, items.size)
        assertEquals("商家转账-来自拼团平台", items[0].name)
        assertEquals("美团", items[1].name)
        assertEquals("美团", items[2].name)
        assertEquals("中国电信", items[3].name)
        assertEquals("转账-来自亲友", items[5].name)
        assertEquals("拼团平台商户-退款", items[7].name)
    }

    @Test
    fun parseLineItemsColumnLayoutWechatBill() {
        // 模拟 ML Kit 中文 OCR 的实际输出：金额单独成列、商家单独成列
        val text = """
            全部账单▼
            2026年8月v
            口支付
            商家转账-来自拼团平台
            8月15日13:58
            美团
            8月15日10:54
            美团
            8月15日10:11
            中国电信
            8月15日09:30
            抖音-手机充值
            8月13日09:27
            转账-来自亲友
            8月11日18:31
            扫二维码付款-给客户
            8月11日18:31
            家常菜馆
            8月11日17:07
            市民超市有限公司
            8月11日16:49
            转账-来自亲友
            8月11日 10:58
            账单
            拼团平台商户-退款
            8月10日 01:13
            收支统计>
            支出¥1819.67 收入¥1859.82
            +1.80
            -7.01
            -5.00
            -29.94
            -10.00
            +50.00
            -30.00
            -19.00
            -15.70
            +100.00
            +7.90
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(11, items.size)

        assertEquals("商家转账-来自拼团平台", items[0].name)
        assertEquals(1.80, items[0].amount, 0.001)
        assertEquals("income", items[0].type)
        // 支付时间：商家转账对应 8月15日13:58（日期紧跟商家之后）
        val ts0 = LocalDateTime.of(2026, 8, 15, 13, 58)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(ts0, items[0].timestamp)

        assertEquals("美团", items[1].name)
        assertEquals(7.01, items[1].amount, 0.001)
        assertEquals("expense", items[1].type)
        // 支付时间：第一笔美团对应 8月15日10:54
        val expectedTs = LocalDateTime.of(2026, 8, 15, 10, 54)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedTs, items[1].timestamp)

        assertEquals("美团", items[2].name)
        assertEquals(5.00, items[2].amount, 0.001)

        assertEquals("中国电信", items[3].name)
        assertEquals(29.94, items[3].amount, 0.001)

        assertEquals("抖音-手机充值", items[4].name)

        assertEquals("转账-来自亲友", items[5].name)
        assertEquals(50.00, items[5].amount, 0.001)
        assertEquals("income", items[5].type)

        assertEquals("家常菜馆", items[7].name)
        assertEquals(19.00, items[7].amount, 0.001)

        assertEquals("市民超市有限公司", items[8].name)
        assertEquals(15.70, items[8].amount, 0.001)

        assertEquals("拼团平台商户-退款", items[10].name)
        assertEquals(7.90, items[10].amount, 0.001)
        assertEquals("income", items[10].type)
    }

    @Test
    fun parseLineItemsMarksRefundedNote() {
        // 模拟微信账单「已全额退款」：注释紧跟被退款那笔金额之下（右列金额区），
        // 其上方的退款入账（+91.63）是独立一笔收入。
        val text = """
            全部账单▼
            2026年8月v
            某某电子有限公司-退款
            8月10日 00:08
            抖音电商商家
            8月9日 11:15
            美团
            8月9日 10:52
            +91.63
            -91.63
            已全额退款
            -12.60
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(3, items.size)

        // 退款入账：收入，不标记为已退款
        assertEquals("某某电子有限公司-退款", items[0].name)
        assertEquals(91.63, items[0].amount, 0.001)
        assertEquals("income", items[0].type)
        assertFalse(items[0].refunded)

        // 原支付：支出，被「已全额退款」注释标记
        assertEquals("抖音电商商家", items[1].name)
        assertEquals(91.63, items[1].amount, 0.001)
        assertEquals("expense", items[1].type)
        assertTrue(items[1].refunded)

        assertEquals("美团", items[2].name)
        assertFalse(items[2].refunded)
    }

    @Test
    fun parseLineItemsMarksRefundedNoteWhenOcrMisreadsE() {
        // 「已全额退款」的「额」常被 ML Kit 误识别成「融」→「已全融退款」，仍应命中
        val text = """
            抖音电商商家
            美团
            -91.63
            已全融退款
            -12.60
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(2, items.size)
        assertEquals("抖音电商商家", items[0].name)
        assertEquals(91.63, items[0].amount, 0.001)
        assertTrue(items[0].refunded)
        assertEquals("美团", items[1].name)
        assertFalse(items[1].refunded)
    }

    @Test
    fun parseLineItemsNormalizesGarbledOcrAmounts() {
        // 减号被识别成「一」、竖线噪声、字母 l 当 1 —— 仍应按纯金额行解析，不丢弃
        val text = """
            美团
            中国电信
            一29.9
            |一34.90
            -12.60
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(2, items.size)
        assertEquals("美团", items[0].name)
        assertEquals(29.9, items[0].amount, 0.001)
        assertEquals("中国电信", items[1].name)
        assertEquals(34.90, items[1].amount, 0.001)
    }

    @Test
    fun parseLineItemsInterleavedPaddleOcr() {
        // PaddleOCR 输出：商家、金额、日期按行交替
        val text = """
            支出￥1819.67收入￥1859.82
            商家转账-来自拼团平台
            +1.80
            8月15日13:58
            美团
            -7.01
            8月15日10:34
            美团
            -5.00
            8月15日10:11
            中国电信
            -29.94
            8月13日09:30
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(4, items.size)
        assertEquals("商家转账-来自拼团平台", items[0].name)
        assertEquals(1.80, items[0].amount, 0.001)
        assertEquals("income", items[0].type)
        assertEquals("美团", items[1].name)
        assertEquals(7.01, items[1].amount, 0.001)
        assertEquals("美团", items[2].name)
        assertEquals("中国电信", items[3].name)
        assertEquals(29.94, items[3].amount, 0.001)
    }

    @Test
    fun parseLineItemsInterleavedAbsorbsNoise() {
        // 「某某百货」是「某某百货」的误读片段、不带金额，应被下一条真商家覆盖，不产生假账
        val text = """
            扫二维码付款-给某某百货
            -3.00
            某某百货
            8月7日23:42
            美团
            -32.49
            8月7日23:12
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(2, items.size)
        assertEquals("扫二维码付款-给某某百货", items[0].name)
        assertEquals(3.00, items[0].amount, 0.001)
        assertEquals("美团", items[1].name)
        assertEquals(32.49, items[1].amount, 0.001)
    }

    @Test
    fun parseLineItemsInterleavedDetectionExcludesDates() {
        // 日期行不能被误判成「商家」，否则布局检测会把交替布局误判成块布局、导致配对错位
        val text = """
            美团
            -7.01
            8月15日10:34
            美团
            美团
            -5.00
            8月15日10:11
            中国电信
            -29.94
            8月13日09:30
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(3, items.size)
        assertEquals("美团", items[0].name)
        assertEquals(7.01, items[0].amount, 0.001)
        assertEquals("美团", items[1].name)
        assertEquals(5.00, items[1].amount, 0.001)
        assertEquals("中国电信", items[2].name)
        assertEquals(29.94, items[2].amount, 0.001)
    }

    @Test
    fun parseAlipayFuzzyDatesAndStatusWords() {
        // 支付宝账单：日期分组标题在条目之前（昨天/前天），
        // 「等待确认收货」照常录入，「交易关闭」标记为关闭
        val photoTakenAt = LocalDateTime.of(2026, 8, 16, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = """
            2026年8月
            支出 ￥123.00 收入 ￥0.00
            昨天
            美团外卖
            -25.00
            等待确认收货
            前天
            滴滴出行
            -18.50
            交易关闭
            8月13日
            猫眼电影
            -45.00
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text, "expense", photoTakenAt)
        assertEquals(3, items.size)

        // 时间倒序：昨天(8/15) > 前天(8/14) > 8月13日
        assertEquals("美团外卖", items[0].name)
        assertEquals(25.0, items[0].amount, 0.001)
        assertFalse(items[0].closed)
        val tsYesterday = LocalDateTime.of(2026, 8, 15, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(tsYesterday, items[0].timestamp)

        assertEquals("滴滴出行", items[1].name)
        assertEquals(18.5, items[1].amount, 0.001)
        assertTrue("交易关闭应被标记", items[1].closed)
        val tsBeforeYesterday = LocalDateTime.of(2026, 8, 14, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(tsBeforeYesterday, items[1].timestamp)

        assertEquals("猫眼电影", items[2].name)
        assertEquals(45.0, items[2].amount, 0.001)
        assertFalse(items[2].closed)
        val ts13 = LocalDateTime.of(2026, 8, 13, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(ts13, items[2].timestamp)
    }

    @Test
    fun parseAlipayMixedLineClosed() {
        // 支付宝 OCR 把「商家 + 金额 + 交易关闭」输出在同一行
        val text = """
            8月15日
            淘宝 三只松鼠旗舰店 -59.90 交易关闭
            美团外卖 -25.00
            昨天
        """.trimIndent()

        val items = ReceiptParser.parseLineItems(text)
        assertEquals(2, items.size)
        assertEquals("淘宝 三只松鼠旗舰店", items[0].name)
        assertEquals(59.9, items[0].amount, 0.001)
        assertTrue(items[0].closed)
        assertEquals("美团外卖", items[1].name)
        assertEquals(25.0, items[1].amount, 0.001)
        assertFalse(items[1].closed)
    }

    @Test
    fun parseAlipayDateWithoutTime() {
        // 支付宝日期分组标题无时刻（如「8月15日」），应解析为当天 12:00 而不是被当商家
        val text = """
            8月15日
            美团
            -7.01
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(1, items.size)
        assertEquals("美团", items[0].name)
        val ts = LocalDateTime.of(LocalDate.now().year, 8, 15, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(ts, items[0].timestamp)
    }

    @Test
    fun parseRealAlipayBill() {
        // 支付宝账单 OCR：共 28 笔（含 5 笔 0.00 押金、7 笔交易关闭、2 笔收入）
        val photoTakenAt = LocalDateTime.of(2026, 8, 17, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = javaClass.classLoader!!.getResourceAsStream("alipay_bill_sample.txt")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val items = ReceiptParser.parseLineItems(text, "expense", photoTakenAt)

        assertEquals("支付宝账单应为 28 笔", 28, items.size)
        assertEquals("交易关闭 7 笔", 7, items.count { it.closed })
        assertEquals("收入 2 笔（+100 / +730）", 2, items.count { it.type == "income" })
        assertEquals("0.00 押金类 5 笔", 5, items.count { it.amount == 0.0 })

        // 商家 + 金额抽查
        val meituan29 = items.first { it.amount == 29.58 }
        assertEquals("某某餐厅超值双人套餐", meituan29.name)
        val yuebao = items.first { it.amount == 0.36 }
        assertEquals("余额宝-转出到银行卡", yuebao.name)
        val xianyu = items.first { it.type == "income" }
        assertEquals(100.0, xianyu.amount, 0.001)

        // 「昨天 13:11」→ 昨天(8/16) 13:11
        val bank25 = items.first { it.amount == 25.0 && !it.closed && it.name == "银行卡定时转入" }
        val tsYesterday1311 = LocalDateTime.of(2026, 8, 16, 13, 11)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(tsYesterday1311, bank25.timestamp)

        // 交易关闭条目默认不勾选：closed 标记
        val closed650 = items.first { it.amount == 650.0 }
        assertTrue(closed650.closed)

        // 内部转移（总资产不变）：余额提现 / 银行卡定时转入 / 余额宝转出
        assertTrue("银行卡定时转入应为内部转移", items.first { it.name == "银行卡定时转入" }.internal)
        assertTrue("余额提现应为内部转移", items.first { it.amount == 34.67 }.internal)
        assertTrue("余额宝-转出到银行卡应为内部转移", items.first { it.amount == 0.36 }.internal)
        // 正常消费不是内部转移
        assertFalse("某某餐厅不是内部转移", items.first { it.amount == 29.58 }.internal)
    }

    @Test
    fun parseRealWechatBill() {
        // 微信账单 OCR：53 笔，其中 3 笔「已全额退款」——确保支付宝适配没破坏微信解析
        val text = javaClass.classLoader!!.getResourceAsStream("wechat_bill_sample.txt")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val items = ReceiptParser.parseLineItems(text)
        assertEquals("微信账单应为 53 笔", 53, items.size)
        assertEquals("已全额退款 3 笔", 3, items.count { it.refunded })
    }

    @Test
    fun dedupeOverlappingRemovesSameTransactionAcrossFrames() {
        // 微信账单滚动截屏：同一笔出现在相邻两屏（重叠区），逐屏 OCR 后应只保留一笔；
        // 同一天视为同一笔（滚动中的模糊帧可能读错具体时刻），不同天则保留
        val ts = 1_720_000_020_000L // 整分钟时间戳
        val items = listOf(
            LineItem("美团", 12.5, "餐饮", timestamp = ts),
            LineItem("美团", 12.5, "餐饮", timestamp = ts + 30_000),      // 同一天 → 去重
            LineItem("美团", 12.5, "餐饮", timestamp = ts + 86_400_000L), // 另一天 → 保留
            LineItem("滴滴", 8.0, "出行", timestamp = ts + 180_000),
        )
        val deduped = ReceiptParser.dedupeOverlapping(items)
        assertEquals(3, deduped.size)
        assertEquals(2, deduped.count { it.name == "美团" })
        assertEquals(1, deduped.count { it.name == "滴滴" })
    }

    @Test
    fun dedupeOverlappingKeepsNullTimestampItems() {
        // 时间缺失时（滚动中的模糊帧读不出时间）：名称+金额一致视为同一笔，去重；
        // 名称或金额不同则保留
        val items = listOf(
            LineItem("美团", 12.5, "餐饮", timestamp = null),
            LineItem("美团", 12.5, "餐饮", timestamp = null),
            LineItem("美团", 12.5, "餐饮", timestamp = null),
            LineItem("滴滴", 8.0, "出行", timestamp = null),
        )
        val deduped = ReceiptParser.dedupeOverlapping(items)
        assertEquals(2, deduped.size)
        assertEquals(1, deduped.count { it.name == "美团" })
        assertEquals(1, deduped.count { it.name == "滴滴" })
    }

    @Test
    fun dedupeOverlappingKeepsSameMerchantDifferentDay() {
        // 同一商家同金额但不同日期：不是同一笔，保留
        val day1 = 1_720_000_000_000L
        val day2 = day1 + 86_400_000L
        val items = listOf(
            LineItem("美团", 12.5, "餐饮", timestamp = day1),
            LineItem("美团", 12.5, "餐饮", timestamp = day2),
        )
        assertEquals(2, ReceiptParser.dedupeOverlapping(items).size)
    }

    @Test
    fun parseLineItemsFiltersOverlayPanelNoise() {
        // 悬浮窗面板浮在微信上方：涂白漏出时会把「已截 N 屏 · 点这里停止」等读进账单，
        // 这些残留行不应入账、不应当商家（否则每帧多一个「停止」，还会错位配对）
        val text = """
            已截 3 屏 · 点这里停止
            停止
            完成
            准备就绪 · 滑动到想截的位置
            美团
            -7.01
            8月15日10:54
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(1, items.size)
        assertEquals("美团", items[0].name)
        assertEquals(7.01, items[0].amount, 0.001)
    }

    @Test
    fun parseDateWithSpaces() {
        // OCR 常把「8月6日」读成「8 月 6 日」：应识别为日期行而非商品名
        val text = """
            8 月 6 日
            美团
            -7.01
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(1, items.size)
        assertEquals("美团", items[0].name)
        val ts = LocalDateTime.of(LocalDate.now().year, 8, 6, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(ts, items[0].timestamp)
    }

    @Test
    fun wechatOvershootPreviousMonthIsDropped() {
        // 微信账单默认走 alipay=false 路径，同样会滑过头滚进更早月份。
        // 目标 8 月 + 误入 7 月：停点日期 8月14 → 应剔除 7 月账目，保留 8 月账目。
        val text = """
            8月15日
            美团
            -7.01
            8月14日
            中国电信
            -29.94
            7月31日
            某某竞拍平台
            -2.90
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text, "expense", LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val filtered = ReceiptParser.dropOvershoot(items)

        assertTrue("8月美团应保留", filtered.any { it.name == "美团" && it.amount == 7.01 })
        assertTrue("8月中国电信应保留", filtered.any { it.name == "中国电信" && it.amount == 29.94 })
        assertFalse("7月竞拍平台应剔除", filtered.any { it.name.contains("竞拍") })
        assertEquals("目标8月保留2笔", 2, filtered.size)
    }

    @Test
    fun wechatSameMonthIsFullyKept() {
        // 同月账目（含月末 8月1、月初 8月6）必须完整保留——
        // 修复「识别少了」：旧逻辑用最后一帧日期做同月停点，误删了合法的 8月1/8月3 等月末账目。
        val items = listOf(
            LineItem("美团", 7.01, "餐饮", type = "expense", timestamp = tsAt(LocalDate.now().year, 8, 6, 10, 0)),
            LineItem("中国电信", 29.94, "生活", type = "expense", timestamp = tsAt(LocalDate.now().year, 8, 5, 9, 0)),
            LineItem("京东", 55.0, "购物", type = "expense", timestamp = tsAt(LocalDate.now().year, 8, 3, 12, 0)),
            LineItem("扫二维码付款", 0.5, "日用", type = "expense", timestamp = tsAt(LocalDate.now().year, 8, 1, 19, 29)),
        )
        val filtered = ReceiptParser.dropOvershoot(items)
        assertEquals("同月账目全部保留", 4, filtered.size)
    }

    @Test
    fun wechatCrossMonthIsDroppedButSameMonthKept() {
        // 目标 8 月 + 月末 8月1 + 误入 7 月：7 月剔除、8 月（含 8月1）完整保留。
        val items = listOf(
            LineItem("美团", 7.01, "餐饮", type = "expense", timestamp = tsAt(LocalDate.now().year, 8, 6, 10, 0)),
            LineItem("扫二维码付款", 0.5, "日用", type = "expense", timestamp = tsAt(LocalDate.now().year, 8, 1, 19, 29)),
            LineItem("某某竞拍平台", 2.9, "购物", type = "expense", timestamp = tsAt(LocalDate.now().year, 7, 31, 23, 53)),
        )
        val filtered = ReceiptParser.dropOvershoot(items)
        assertTrue("8月6保留", filtered.any { it.name == "美团" })
        assertTrue("8月1保留（修复识别少了）", filtered.any { it.name == "扫二维码付款" })
        assertFalse("7月竞拍平台剔除", filtered.any { it.name.contains("竞拍") })
        assertEquals(2, filtered.size)
    }

    @Test
    fun wechatInvalidZeroMonthDayIsNotABill() {
        // OCR 把微信帧首「年份+月度总额」错读成「0月0日10.10」：不得产出「月 日」假账。
        // 月=0/日=0 无法构成真实日期，整行应作为噪声丢弃。
        val text = """
            停止
            2026年
            2939.82
            0月0日10.10
            某某螺蛳粉 (大学城店)
            -16.00
            美团
            8月6日12:20
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text, "expense", LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alipay = false)
        // 不应出现「月 日」这类假账，也不该有金额 10.1
        assertFalse("不应出现「月 日」假账", items.any { it.name.replace(" ", "") == "月日" })
        assertFalse("不应有 10.1 假金额", items.any { it.amount == 10.1 })
        // 条目「某某螺蛳粉」仍保留
        assertTrue("商家应保留", items.any { it.name.contains("螺蛳粉") })
    }

    @Test
    fun wechatNormalMonth10And05AreNotMisFiltered() {
        // 「10月」含 0月 子串、「8月05日」含 05日：都不得被非法月日规则误伤
        val text = """
            10月15日
            美团
            -7.01
            8月05日
            中国电信
            -29.94
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text, "expense", LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            alipay = false)
        assertEquals("10月/8月05日的正常日期行不得被过滤", 2, items.size)
    }

    private fun tsAt(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

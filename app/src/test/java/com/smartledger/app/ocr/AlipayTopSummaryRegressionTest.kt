package com.smartledger.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付宝账单页顶部截图回归测试：
 * 页面顶部带「我的消费图鉴/收支分析/本月已省」汇总卡片 + 广告横幅时，
 * 汇总金额（¥80/¥830.00/¥1,306.87）不得入账、平台标识（闲鱼/天猫）不得当商家、
 * 分类标签（日用百货/家居家装）不得当商家，交易条目（含「随身WiFi」等）金额配对正确。
 *
 * 数据说明：本测试使用虚构示例数据（商家/商品名均为占位符），仅保留支付页面结构，
 * 用于回归「顶部汇总卡片金额不入账 + 平台标识/分类标签不当作商家」的过滤逻辑。
 */
class AlipayTopSummaryRegressionTest {

    // 模拟支付宝账单页顶部截图 OCR 输出（时间戳为任意示例值）
    private val fakeOcrText = """
        15:22 
        搜索交易记录
        搜索
        全部
        支出
        转账
        退款
        订单
        筛选
        我的消费图鉴>
        8月
        今年累计已省
        支出
        收入
        ￥80
        ￥ 830.00
        ￥ 1,306.87
        收支分析
        本月已省0.53元>
        某某随身WiFi服务 无限速版 .．-31.30
        闲鱼
        日用百货
        今天13:33
        守约完成解冻押金
        0.00
        信用借还
        解冻成功
        昨天12:00
        余额宝-自动转入
        99.40
        投资理财
        昨天11:50
        分账-基础软件服务费(0000000000000.：-0.60
        商业服务
        昨天09:22
        天猫
        已接某某智能插座WiFi版..
        -37.90
        家居家装
        等待确认收货
        08-1916:22
        某某交易平台
    """.trimIndent()

    @Test
    fun topSummaryCardAmountsMustNotBecomeTransactions() {
        val items = ReceiptParser.parseLineItems(fakeOcrText)
        // 顶部汇总金额（80/830/1306.87）和广告金额（31.3）都不应产生条目；
        // 只应有 5 笔真实交易
        assertEquals(5, items.size)

        // 第一笔：真实交易「某某随身WiFi服务」-31.30
        assertEquals("某某随身WiFi服务 无限速版", items[0].name)
        assertEquals(31.3, items[0].amount, 0.001)
        assertEquals("expense", items[0].type)

        // 守约完成解冻押金 0.00（0 元押金解冻）
        assertEquals("守约完成解冻押金", items[1].name)
        assertEquals(0.0, items[1].amount, 0.001)

        // 余额宝-自动转入 99.40：资金搬运（总资产不变）→ 内部转移，默认不勾选
        assertEquals("余额宝-自动转入", items[2].name)
        assertEquals(99.4, items[2].amount, 0.001)
        assertTrue("余额宝-自动转入应为内部转移", items[2].internal)

        // 分账-基础软件服务费 -0.60
        assertEquals("分账-基础软件服务费", items[3].name)
        assertEquals(0.6, items[3].amount, 0.001)

        // 已接某某智能插座WiFi版 -37.90
        assertEquals("已接某某智能插座WiFi版", items[4].name)
        assertEquals(37.9, items[4].amount, 0.001)

        // 平台标识（闲鱼/天猫）、分类标签（日用百货/家居家装）不得成为商家名
        assertFalse(items.any { it.name == "闲鱼" || it.name == "天猫" || it.name == "日用百货" || it.name == "家居家装" })
        // 顶部汇总金额不得出现
        assertFalse(items.any { it.amount == 80.0 || it.amount == 830.0 || it.amount == 1306.87 })
    }

    @Test
    fun wechatMerchantNamedMeituanMustNotBeFiltered() {
        // 微信账单里「美团」整行是真实商家（不是平台标识），不能被 ALIPAY_PLATFORM_TAGS 过滤
        val text = """
            美团
            -7.01
            8月15日10:34
            美团
            -5.00
            8月15日10:11
            中国电信
            -29.94
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(3, items.size)
        assertEquals("美团", items[0].name)
        assertEquals(7.01, items[0].amount, 0.001)
        assertEquals("美团", items[1].name)
        assertEquals(5.00, items[1].amount, 0.001)
        assertEquals("中国电信", items[2].name)
    }

    @Test
    fun purePlatformTagLinesAreFilteredButContainingLinesKept() {
        // 「闲鱼寄件-寄件费」含「闲鱼」但不是整行平台标识 → 必须保留
        val text = """
            闲鱼寄件-寄件费
            -6.90
            8月16日15:41
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(1, items.size)
        assertEquals("闲鱼寄件-寄件费", items[0].name)
        assertEquals(6.9, items[0].amount, 0.001)
    }

    @Test
    fun balanceAndLingqiantongTransfersAreInternal() {
        // 余额宝/零钱通自动转入转出：资金搬运，总资产不变 → internal=true（默认不勾选保存）
        val text = """
            余额宝-自动转入
            99.40
            投资理财
            昨天11:50
            零钱通-自动转入
            50.00
            理财
            昨天10:00
            余额宝转出到银行卡
            200.00
            账户存取
            前天09:00
            银行卡定时转入
            25.00
            投资理财
            8月13日09:30
        """.trimIndent()
        val items = ReceiptParser.parseLineItems(text)
        assertEquals(4, items.size)
        assertTrue("余额宝-自动转入应为内部转移", items[0].internal)
        assertTrue("零钱通-自动转入应为内部转移", items[1].internal)
        assertTrue("余额宝转出到银行卡应为内部转移", items[2].internal)
        assertTrue("银行卡定时转入应为内部转移", items[3].internal)
        // 普通消费不是内部转移
        val normal = ReceiptParser.parseLineItems("美团\n-7.01\n8月15日10:34")
        assertEquals(1, normal.size)
        assertFalse("普通消费不是内部转移", normal[0].internal)
    }

    @Test
    fun sameBillJudgedOnlyBySecondAndAmount() {
        // 与已导入账目判重：同分同秒 + 同金额即重复，不看类型/商家名
        val ts = 1_720_000_000_000L

        // 同秒同金额 → 重复（不管名字、类型）
        assertTrue(ReceiptParser.isSameBill(12.5, ts, 12.5, ts))
        assertTrue(ReceiptParser.isSameBill(12.5, ts, 12.5, ts + 999)) // 同一秒内
        // 不同秒 → 不重复
        assertFalse(ReceiptParser.isSameBill(12.5, ts, 12.5, ts + 1_000))
        // 金额不同 → 不重复
        assertFalse(ReceiptParser.isSameBill(12.5, ts, 12.51, ts))
        // 时间缺失 → 不重复
        assertFalse(ReceiptParser.isSameBill(12.5, null, 12.5, ts))
        // 跨分钟（同一分钟内的不同秒不同）→ 不重复
        assertFalse(ReceiptParser.isSameBill(12.5, ts, 12.5, ts + 60_000))
    }
}
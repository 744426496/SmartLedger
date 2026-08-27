package com.smartledger.app.ocr

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** OCR 解析出的单条候选记录 */
data class ExtractedInfo(
    val type: String = "expense",        // "expense" / "income"
    val amount: Double? = null,          // 金额（元）
    val merchant: String? = null,        // 商家/收款方
    val category: String? = null,        // 自动归类
    val timestamp: Long? = null,         // 交易时间（epoch 毫秒）
    val rawText: String = "",            // OCR 原始文本，便于人工复核
) {
    companion object {
        const val TYPE_EXPENSE = "expense"
        const val TYPE_INCOME = "income"
    }
}

/** 一行明细（品名 + 金额 + 自动分类 + 收支类型 + 支付时间），用于「一张图多笔记账」 */
data class LineItem(
    val name: String,
    val amount: Double,
    val category: String,
    val type: String = ExtractedInfo.TYPE_EXPENSE,
    val timestamp: Long? = null,
    val refunded: Boolean = false,   // 是否已被全额退款（微信账单右侧「已全额退款」）
    val closed: Boolean = false,     // 是否「交易关闭」（支付宝：扣款失败，未实际支出）
    val internal: Boolean = false,   // 是否「内部转移」（余额提现/银行卡转入等，总资产不变）
)

/**
 * 从 OCR 文本中解析金额、商家、时间、收支类型。
 *
 * 解析策略（全部基于启发式规则，不依赖网络/大模型）：
 *  1. 类型：命中"工资/收入/入账/到账/存入"等词 → 收入，否则默认支出
 *  2. 金额：优先取"合计/实收/实付/金额"等关键词行中的数字；其次带 ¥/￥ 的数字；最后取最大可疑金额
 *  3. 商家：优先"收款方/商家/商户名称"等字段值；否则取首行像店名的短文本
 *  4. 时间：解析 "2024年12月31日 18:30" 之类日期；解析不到时回退为拍照时间
 */
object ReceiptParser {

    /**
     * 与已导入账目判重（按用户要求简化）：金额相同（±0.005 内）+ 支付时间同年同月同日
     * 同分同秒（精确到秒）即判重复；不再比较类型/商家名——OCR 对商家名可能多/漏字符，
     * 名字比对反而漏判。任一方时间缺失不算重复。
     */
    fun isSameBill(amount1: Double, ts1: Long?, amount2: Double, ts2: Long): Boolean {
        if (kotlin.math.abs(amount1 - amount2) > 0.005) return false
        val a = ts1 ?: return false
        return a / 1_000 == ts2 / 1_000
    }

    /**
     * 丢弃无时间戳的 OCR 误读残留：滚动截屏中同一笔必在相邻帧带完整日期出现，
     * 若某条目无时间戳、但批次中存在「同名（互相包含）且金额接近」的有时间戳条目，
     * 则它是误读/截断版，直接丢弃，避免幽灵记账。
     *
     * 注意必须**同时比金额**，但不能过严：同名不同金额是真实的不同账单不能删
     * （如「余额提现 34.67」与「余额提现 31.92」各是一笔，后者日期行被截断成无时间戳，
     * 只按名字会误删、导致「识别少了」）；而 OCR 里金额又常被拆行/舍入成近似值
     * （「34.67」被读成「34」+「167」→ 34.0，与真身差 0.67）。故取「金额差 < 1 元」为
     * 同一笔截断版的判据：能吸收 OCR 拆分/舍入误差，又能区分相差数元的真实不同账单。
     *
     * 必须在 [expandRefunds] 之前调用——退款成对会删掉同名真身，之后再过滤就找不到了。
     */
    fun dropUntimedGhosts(items: List<LineItem>): List<LineItem> {
        val timed = items.filter { it.timestamp != null }
        return items.filter { item ->
            item.timestamp != null || timed.none {
                namesOverlap(it.name, item.name) && kotlin.math.abs(it.amount - item.amount) < 1.0
            }
        }
    }

    /**
     * 丢弃「滑过头」滚进更早月份的账目（支付宝/微信滚动截屏都可能发生）。
     *
     * 用户从账单页顶部（最新一月，如 8 月）往下滑，目标是「当月」；一不留神滑过月界、
     * 滚进上一个自然月（如 7 月），那一屏的账目属于滑过头的误入（实测「74 笔多了几笔」）。
     *
     * 判定只做**跨月**过滤、不做**同月日级**截断——后者曾实测误删合法的月末账目
     * （微信最后一帧停在 8月2，把 8月1 的账单也删了，导致「识别少了」）：
     *   - 目标月份 = 批次内最新账目所在月（账单页顶部的最新交易月，如 8 月）；任何早于目标
     *     月份的条目（如 7 月）属于滑过头的误入，整组丢弃；
     *   - 目标月内（含月初到月尾的全部账目）一律保留，不再按「最后一帧日期」微调停点。
     *
     * 这样：滚进 7 月 → 7 月整组剔除；目标 8 月的账（含 8月1 日）完整保留；单月批次不受影响。
     */
    fun dropOvershoot(items: List<LineItem>): List<LineItem> {
        val zone = ZoneId.systemDefault()
        // 目标月份 = 最新账目所在月（跨到目标月「第一天」作为下界，早于它等于更早月份）
        val targetMonthFirst = items.mapNotNull { it.timestamp }
            .maxOrNull()
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().withDayOfMonth(1) }
            ?: return items

        return items.filter { item ->
            val ts = item.timestamp ?: return@filter true // 无时间戳（正常入账）保留
            val d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
            // 只丢弃早于目标月的更早月份（如 7 月）；目标月及以后全部保留
            d >= targetMonthFirst
        }
    }

    /**
     * 去掉同一批次内完全相同的重复条目：
     * 微信账单滚动截屏时，同一笔会同时出现在相邻两屏（重叠区），逐屏 OCR 后需去重；
     * 多张截图导入（同一笔出现在两张图中）同样受益。
     *
     * 判定：类型、金额、商家名一致（或互相包含——OCR 对同一商家名在不同帧可能多/漏
     * 字符，如「某某螺蛳粉 (大学城店)」被拆行/加噪声字后与完整名不一致），且支付时间在
     * 同一分钟（滚动重叠区同一笔的日期行读出的时刻一致）；任一方时间缺失（模糊帧读不出
     * 时间）也视为同一笔，并优先保留有时间戳的条目（避免「fallback 到识别时刻」的幽灵
     * 条目与真实条目并存）。商家名取较长者（信息更完整）。状态标记取并集，防止跨帧丢失。
     *
     * 注意：这里必须保留类型+名字比较——退款成对（支出 -91.63 与「退款·」收入 +91.63
     * 同金额同时刻）和同一分钟内的两笔真实同额转账（如「转账-来自某甲 +50」与
     * 「转账-来自某乙 +50」）都靠名字/类型区分，只看金额+时间会把它们误合并。
     */
    fun dedupeOverlapping(items: List<LineItem>, alipay: Boolean = false): List<LineItem> {
        // 先丢弃无时间戳的误读残留（复用 dropUntimedGhosts 的过滤逻辑），
        // 再对剩余条目做跨帧合并去重
        val cleaned = dropUntimedGhosts(items)

        val kept = mutableListOf<LineItem>()
        for (item in cleaned) {
            val idx = kept.indexOfFirst { k ->
                k.type == item.type &&
                    abs(k.amount - item.amount) < 0.005 &&
                    namesOverlap(k.name, item.name) &&
                    (if (alipay) sameBillDay(k.timestamp, item.timestamp) else sameBillMoment(k.timestamp, item.timestamp))
            }
            if (idx >= 0) {
                val k = kept[idx]
                // 优先保留有时间戳的条目（幽灵 null 帧被真实帧吸收），商家名取较长者，状态取并集
                val base = if (k.timestamp == null && item.timestamp != null) item else k
                val name = if (item.name.length > k.name.length) item.name else k.name
                kept[idx] = base.copy(
                    name = name,
                    refunded = k.refunded || item.refunded,
                    closed = k.closed || item.closed,
                    internal = k.internal || item.internal,
                )
            } else {
                kept.add(item)
            }
        }
        return kept
    }

    /** 两个商家名是否同一家：完全相等，或一方包含另一方（OCR 变体：拆行/噪声字导致名不一致） */
    private fun namesOverlap(a: String, b: String): Boolean {
        if (a == b) return true
        val na = a.replace(" ", "")
        val nb = b.replace(" ", "")
        return na.contains(nb) || nb.contains(na)
    }

    /** 是否为同一笔：支付时间在同一分钟内（滚动重叠区同一笔的日期行读出的时刻一致），
     * 或至少一方时间缺失（模糊帧读不出时间）。同一分钟比「同一天」更精确——
     * 同一天同名同额的真实两笔（如两笔一卡通 3.00）不会被误合并。 */
    private fun sameBillMoment(a: Long?, b: Long?): Boolean {
        if (a == null || b == null) return true
        return a / 60_000 == b / 60_000
    }

    /** 支付宝专用的跨帧判重：同一笔在滚动重叠帧里，日期行可能会被读成相邻的「昨天/今天」
     * 或分/秒略有误差（如「08-1410:01」vs「08-14 10:01」），同分钟过于严格会漏合；
     * 放宽到「同一自然日」——同一笔在同一天必然同日，不同天同名同额的真实两笔（如
     * 多次「银行卡定时转入 25.00」）不会被误合并。 */
    private fun sameBillDay(a: Long?, b: Long?): Boolean {
        if (a == null || b == null) return true
        val da = Instant.ofEpochMilli(a).atZone(ZoneId.systemDefault()).toLocalDate()
        val db = Instant.ofEpochMilli(b).atZone(ZoneId.systemDefault()).toLocalDate()
        return da == db
    }

    private val AMOUNT_KEYWORDS = listOf(
        "合计", "总计", "实收", "实付", "应付", "应收", "金额", "小计", "消费",
        "付款", "支付", "入账", "收入", "工资", "到账", "交易", "本单", "本笔",
    )

    private val INCOME_KEYWORDS = listOf(
        "工资", "收入", "入账", "进账", "到账", "存入", "转入", "收到", "奖金", "薪金", "入金",
    )

    private val AMOUNT_REGEX = Regex("""[¥￥]\s*(\d{1,9}(?:,\d{3})*(?:\.\d{1,2})?)|\b(\d{1,9}(?:,\d{3})*(?:\.\d{1,2})?)\b""")

    // 收款方/商家 类字段（商家名）
    private val MERCHANT_FIELD_REGEX = Regex(
        """(?:收款方|收款单位|收款账户|商家|商户|商户名称|商户名|店名|店铺)[:：]?\s*([\u4e00-\u9fa5A-Za-z0-9·（）()\-]{2,30})"""
    )

    // 付款方 类字段（兜底：没有商家字段时，付款方可能是转账对象）
    private val PAYER_FIELD_REGEX = Regex(
        """付款方[:：]?\s*([\u4e00-\u9fa5A-Za-z0-9·（）()\-]{2,30})"""
    )

    private val DATE_REGEX = Regex("""(20\d{2})[年\-/.](\d{1,2})[月\-/.](\d{1,2})[日号]?""")
    private val TIME_REGEX = Regex("""(\d{1,2})[:：时](\d{1,2})(?:[:：分]?(\d{1,2}))?""")

    fun parse(text: String, photoTakenAt: Long = System.currentTimeMillis()): ExtractedInfo {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val whole = text.replace("\n", " ")

        val type = detectType(whole)
        val amount = extractAmount(lines, whole)
        val merchant = extractMerchant(lines)
        val category = CategoryClassifier.classify(whole, type, merchant)
        val timestamp = extractTimestamp(whole) ?: photoTakenAt

        return ExtractedInfo(
            type = type,
            amount = amount,
            merchant = merchant,
            category = category,
            timestamp = timestamp,
            rawText = text,
        )
    }

    // ---------- 类型 ----------

    private fun detectType(whole: String): String =
        if (INCOME_KEYWORDS.any { whole.contains(it) }) ExtractedInfo.TYPE_INCOME
        else ExtractedInfo.TYPE_EXPENSE

    // ---------- 金额 ----------

    private fun extractAmount(lines: List<String>, whole: String): Double? {
        // 1. 含金额关键词的行
        for (line in lines) {
            if (AMOUNT_KEYWORDS.any { line.contains(it) }) {
                parseAmount(line)?.let { return it }
            }
        }
        // 2. 整段文本中带 ¥/￥ 的数字
        parseAmount(whole)?.let { return it }

        // 3. 兜底：优先取带小数的最大金额（年份等整数不会误选），否则取最大整数金额
        val candidates = AMOUNT_REGEX.findAll(whole)
            .mapNotNull { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(",", "").toDoubleOrNull() }
            .filter { it > 0 }
            .toList()
        return preferDecimal(candidates)
    }

    /** 从一行文本中解析金额；只认 0.01 ~ 9999999.99 的合理值，避开年份/数量 */
    private fun parseAmount(line: String): Double? {
        val nums = AMOUNT_REGEX.findAll(line)
            .mapNotNull { m ->
                val raw = m.groupValues[1].ifBlank { m.groupValues[2] }
                raw.replace(",", "").toDoubleOrNull()
            }
            .filter { it in 0.01..9_999_999.99 }
            .toList()
        return preferDecimal(nums)
    }

    /** 优先取带小数的金额（更可能是真实金额，而不是年份/数量），其次取最大整数 */
    private fun preferDecimal(nums: List<Double>): Double? {
        if (nums.isEmpty()) return null
        val withDecimal = nums.filter { it % 1 != 0.0 }
        return withDecimal.maxOrNull() ?: nums.maxOrNull()
    }

    // ---------- 商家 ----------

    private fun extractMerchant(lines: List<String>): String? {
        // 1. 优先：收款方/商家/商户 等字段（这些才是商家名）
        for (line in lines) {
            MERCHANT_FIELD_REGEX.find(line)?.let {
                val name = it.groupValues[1].trim()
                if (name.length >= 2) return name
            }
        }
        // 2. 兜底：取第一行"像店名"的短文本
        for (line in lines) {
            val candidate = line
                .substringBefore(" ")
                .trim()
                .removeSuffix("：").removeSuffix(":")
            if (looksLikeMerchant(candidate)) return candidate
        }
        // 3. 最后兜底：付款方（如转账收款场景）
        for (line in lines) {
            PAYER_FIELD_REGEX.find(line)?.let {
                val name = it.groupValues[1].trim()
                if (name.length >= 2) return name
            }
        }
        return null
    }

    private fun looksLikeMerchant(s: String): Boolean {
        if (s.length !in 2..20) return false
        if (!s.any { it in '\u4e00'..'\u9fa5' }) return false      // 需要含中文
        if (AMOUNT_KEYWORDS.any { s.contains(it) }) return false     // 排除"合计"等词行
        if (s.contains("￥") || s.contains("¥")) return false
        if (AMOUNT_REGEX.containsMatchIn(s)) return false            // 排除带数字金额的行
        return true
    }

    // ---------- 时间 ----------

    private fun extractTimestamp(whole: String): Long? {
        val dateMatch = DATE_REGEX.find(whole) ?: return null
        val year = dateMatch.groupValues[1].toInt()
        val month = dateMatch.groupValues[2].toInt()
        val day = dateMatch.groupValues[3].toInt()

        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null

        var hour = 12
        var minute = 0
        TIME_REGEX.find(whole)?.let {
            val h = it.groupValues[1].toIntOrNull()
            val m = it.groupValues[2].toIntOrNull()
            if (h != null && h in 0..23 && m != null && m in 0..59) {
                hour = h
                minute = m
            }
        }
        return date.atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // ---------- 多行明细（一张图多笔） ----------

    // 汇总行：这些是合计/总额/月度汇总，不应作为单个项目
    private val SUMMARY_KEYWORDS = listOf(
        "合计", "总计", "小计", "实收", "实付", "应收", "应付", "找零", "优惠",
        "折扣", "抹零", "总额", "消费金额", "实付金额", "收款金额", "本单", "本笔",
        "支出", "收入", "收支",
    )

    // 元信息行：日期/单号/门店/支付方式等，不含商品
    private val META_KEYWORDS = listOf(
        "日期", "时间", "单号", "订单号", "门店", "电话", "地址", "会员费", "会员卡", "收银",
        "发票", "税率", "价税", "工号", "欢迎", "谢谢", "付款方式", "支付方式",
        "温馨提示", "营业时间", "客服", "收支统计", "账单", "查找", "统计", "支付",
        // 支付宝账单界面噪声（顶部菜单/月度总览）：不入账。
        // 「搜索/筛选/全部/订单/收支分析」由 TAB_EXACT_WORDS 仅整行匹配，
        // 「订单0000000000」等真实订单号也是元信息，不能做 contains 匹配，否则真实订单号会被误过滤。
        "图鉴", "已省", "本月", "消费",
        // 系统截图提示（无障碍自动截屏时可能拍进帧里）：不入账
        "截取屏幕截图", "截图",
    )

    // 带符号金额：可选 ¥/￥ 前缀 + 可选正负号 + 数字（支持千分位、4 位以上整数与小数）。
    // 整数部分用贪婪 \d{1,9}（可接千分位组）：无千分位逗号的 4 位金额（如 1200.00）
    // 若写成 \d{1,3} 会被截成 3 位（120）+ 余数，导致金额少一位。
    private val SIGNED_NUMBER_REGEX =
        Regex("""[¥￥]?\s*([+-]?)\s*\d{1,9}(?:,\d{3})*(?:\.\d{1,2})?""")

    // 以下正则处于逐行 OCR 解析热路径，必须复用，避免每笔/每行重新编译 Pattern。
    private val PURE_AMOUNT_CHARS_REGEX = Regex("""[¥￥+\-\d.,\s]""")
    private val ITEM_NAME_PUNCTUATION_REGEX =
        Regex("""[：:，,。.;；、·•"'“”‘’()（）【】\[\]*xX×#+¥￥<>《》!！?？/\\|~]""")
    private val ITEM_NAME_UNIT_REGEX = Regex("""(?i)\b(ml|kg|g|l|rmb|元|零钱|余额)\b""")
    private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

    // ---- 常用正则（提为常量，避免每次调用都重新编译） ----

    /** 紧邻数字的「一」→ 负号（微信账单减号常被识别成「一」） */
    private val YI_TO_MINUS_REGEX = Regex("""一(?=\d)""")

    /** 紧邻数字的字母 l/I → 1（如「l6」→「16」） */
    private val L_TO_ONE_REGEX = Regex("""[lI](?=\d)""")

    /** 括号内内容（「(大学城店)」→「大学城店」） */
    private val BRACKET_INNER_REGEX = Regex("""[（(]\s*([^）)]+?)\s*[）)]""")

    /** 「X月X日 [HH:mm]」（微信；时刻可省略 → 12:00） */
    private val DATE_CN_REGEX = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日(?:\s*(\d{1,2})[:：](\d{1,2}))?""")

    /** 「MM-dd HH:mm」（支付宝；要求带时刻避免与金额混淆） */
    private val DATE_DASH_REGEX = Regex("""(?<!\d)(\d{1,2})-(\d{1,2})\s*(\d{1,2})[:：](\d{1,2})(?!\d)""")

    /** 行内「HH:mm」时刻 */
    private val HOUR_MINUTE_REGEX = Regex("""(\d{1,2})[:：](\d{1,2})""")

    /** 带时刻的日期行（「8月15日 10:34」/「昨天13:11」/「08-16 16:19」） */
    private val WECHAT_DATE_LINE_REGEX =
        Regex("""(?:\d{1,2}\s*月\s*\d{1,2}\s*日|\d{1,2}-\d{1,2}|今天|昨天|前天)\s*\d{1,2}[:：]\d{1,2}""")

    /** 年份（「2026年」→ 2026） */
    private val YEAR_REGEX = Regex("""(20\d{2})\s*年""")

    /** 月界标题行：年份 + 月份（如「2026年8月」「2026 年 8 月」「2026年 8月」） */
    private val MONTH_TITLE_REGEX = Regex("""20\d{2}年\d{1,2}月""")

    /** 纯年份标题（如「2026年」）：月界顶部年份，不是交易 */
    private val YEAR_ONLY_REGEX = Regex("""20\d{2}年""")

    /** 纯月份标签（如「8月」「12月」）：月界标题/月份选择，不是交易 */
    private val MONTH_ONLY_REGEX = Regex("""\d{1,2}月""")

    /** 月=0 的非法月日（OCR 把帧首年份/总额读成「0月0日」；前面不能是数字，避免误伤「10月」） */
    private val INVALID_ZERO_MONTH_REGEX = Regex("""(?<!\d)[0Oo]\s*月""")

    /** 日以 0 开头且紧跟「日」的非法月日（「0月0日」「X月0日」；「10月05日」里 0 后是 5 不是日，不误伤） */
    private val INVALID_ZERO_DAY_REGEX = Regex("""月\s*[0Oo]\s*日""")

    /** 月界标题行首尾的 OCR 装饰符号（「2026年8月v」「2026年7月√」「8月>」等尾巴） */
    private val MONTH_TITLE_EDGE_CHARS = "√v～>▼▽↓·•-·.".toCharArray()

    // 微信账单「已退款」注释行：独立一行、短、不含金额，注释其上一笔交易。
    // 只认「退款」这个稳定子串——「已全额退款」常被 ML Kit 误识别成「已全融退款」（额→融），
    // 若匹配完整短语会漏掉。
    private val REFUND_NOTE_CORE = "退款"

    // 支付宝账单状态词：
    //  - 「等待确认收货」：交易正常进行中，照常录入（从商家名中清洗该字样即可）；
    //  - 「交易关闭」：扣款失败/订单关闭，用户未实际支出，识别后默认不勾选保存。
    private const val AWAIT_SHIP_WORD = "确认收货"
    private const val CLOSED_WORD = "交易关闭"

    // 需要整行精确匹配才过滤的顶部 tab 词（不能 contains，否则误伤「转账-来自XX」等真实条目）
    private val TAB_EXACT_WORDS = setOf("转账", "退款", "订单", "全部", "搜索", "筛选", "收支分析", "8月", "7月")

    // 悬浮窗面板残留词：截屏时面板浮在微信上方，涂白若漏出会把面板文字读进账单。
    // 标题按特征词 contains 过滤；按钮文字按整行精确过滤，避免误伤含这些字的真实商户。
    private val OVERLAY_TITLE_WORDS = listOf("已截", "点这里停止", "准备就绪", "滑动到想截")
    private val OVERLAY_BUTTON_WORDS = setOf("停止", "开始", "完成", "取消")

    // 支付宝条目的分类标签行（独立短行，非商家名），长度 ≤ 6 时过滤
    private val ALIPAY_TAG_WORDS = listOf(
        "餐饮美食", "数码电器", "生活服务", "商业服务", "账户存取", "转账红包",
        "信用借还", "投资理财", "文化休闲", "医疗健康", "交通出行", "酒店旅游",
        "教育公益", "充值提现", "生活缴费", "押金", "宠物生活",
        "日用百货", "家居家装", "服饰装扮", "美容美发", "运动户外", "母婴亲子",
        "图书文娱", "出行旅游", "通讯物流", "教育培训", "公益捐赠",
    )

    // 支付宝账单条目内的平台标识行（独立短行，整行相等才过滤，如「闲鱼」「天猫」「淘」）：
    // 真实交易里它是「商家名 / 金额 / 平台标识 / 分类 / 日期」条目的一部分，不是独立商家。
    // 注意：只放「几乎不可能作为商家名」的纯平台词——美团/京东/拼多多/抖音/盒马/哈啰/菜鸟等
    // 既是平台也是常见商家名（微信账单里「美团」整行是真实商家），绝不能进这个表，
    // 否则会把真实交易过滤掉。此表只收阿里系纯平台标识（用户实际账单中出现过）。
    private val ALIPAY_PLATFORM_TAGS = setOf(
        "闲鱼", "天猫", "淘", "淘宝", "飞猪", "大麦", "口碑", "优酷", "饿了么商家版",
    )

    // 支付宝条目状态行（跳过、不入账不设商家）：与「交易关闭」不同，这些是正常状态
    private val SKIP_STATUS_WORDS = listOf(
        "确认收货", "解冻成功", "免押下单成功", "下单成功", "支付成功", "退款成功", "交易成功",
    )

    // 内部转移类条目：余额提现/银行卡定时转入/余额宝转出等，只是资金换地方保存，总资产不变。
    // 识别后默认不勾选保存并提示用户。
    private val INTERNAL_TRANSFER_WORDS = listOf(
        "余额提现", "提现", "银行卡定时转入", "银行卡转入", "定时转入",
        "余额宝-转出", "余额宝转出", "余额宝转入", "零钱通", "转入零钱", "转出零钱",
        // 「余额宝-自动转入」「零钱通-自动转入」等：中间的「-自动」会让 contains("余额宝转入")
        // 匹配不到，需补「自动转入/自动转出」——同样是资金搬运（总资产不变），默认不勾选。
        "自动转入", "自动转出",
    )

    /** 是否内部转移条目（商家名命中关键词） */
    private fun isInternalTransfer(name: String): Boolean =
        name.isNotBlank() && INTERNAL_TRANSFER_WORDS.any { name.contains(it) }

    /** 支付宝状态行（跳过类，非「交易关闭」）：独立一行、短、不含金额 */
    private fun isAwaitShipNoteLine(line: String): Boolean {
        if (SIGNED_NUMBER_REGEX.containsMatchIn(line)) return false  // 含金额的混合行交给行内处理
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        return cleaned.length <= 10 && SKIP_STATUS_WORDS.any { cleaned.contains(it) }
    }

    /** 支付宝「交易关闭」独立注释行：无金额、标记其上一笔为关闭（扣款失败） */
    private fun isClosedNoteLine(line: String): Boolean {
        if (SIGNED_NUMBER_REGEX.containsMatchIn(line)) return false  // 含金额的混合行交给行内处理
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        return cleaned.length <= 10 && cleaned.contains(CLOSED_WORD)
    }

    /** 界面噪声行（顶部菜单/月度总览/月份标题/分类标签/悬浮窗面板残留/状态栏图标）：不入账、不当商家 */
    private fun isUINoiseLine(line: String): Boolean {
        if (isMonthTitleLine(line)) return true
        // OCR 常把帧首「年份/总额」错读成「0月0日10.10」这类非法月日+金额；
        // 月=0/日=0 无法构成真实日期，整行当噪声，避免「月 日」被当成商家配出假账。
        if (isInvalidMonthDayNoise(line)) return true
        if (line.trim() in TAB_EXACT_WORDS) return true
        // 支付宝分类标签独立短行（如「餐饮美食」），只过滤长度 ≤ 6 的，避免误伤含词的商家名
        if (line.trim().length <= 6 && ALIPAY_TAG_WORDS.any { line.contains(it) }) return true
        // 支付宝平台标识独立短行（整行相等，如「闲鱼」「天猫」「淘」）：
        // 是「商家名/金额/平台标识/分类/日期」条目的一部分，不是独立商家
        if (line.trim() in ALIPAY_PLATFORM_TAGS) return true
        if (isOverlayNoiseLine(line)) return true
        // 状态栏图标文字（「5G」「4G」「WiFi」「G」等）：截图顶部状态栏常被 OCR 扫进去，
        // 纯数字+字母组合且无中文，不可能是真实商家名或金额，整行当噪声丢弃。
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        if (cleaned.length in 1..6 && cleaned.all { it.isLetterOrDigit() } && cleaned.none { it in '\u4e00'..'\u9fa5' }) {
            return true
        }
        return false
    }

    /** 悬浮窗面板残留行（截屏面板浮在微信上方，涂白漏出时的兜底过滤） */
    private fun isOverlayNoiseLine(line: String): Boolean {
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        if (cleaned.isEmpty()) return false
        if (OVERLAY_TITLE_WORDS.any { cleaned.contains(it) }) return true
        if (cleaned in OVERLAY_BUTTON_WORDS) return true
        return false
    }

    /**
     * 模糊日期词（支付宝「昨天/前天/今天」）：相对 [photoDate] 推算日期；
     * 时刻优先取行内时间（如「昨天13:11」→ 昨天 13:11），无时刻取 12:00 居中。
     */
    private fun parseFuzzyDateLine(line: String, photoDate: LocalDate): Long? {
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        if (cleaned.length > 8) return null
        val offset = when {
            cleaned.contains("今天") -> 0L
            cleaned.contains("昨天") -> -1L
            cleaned.contains("前天") -> -2L
            else -> return null
        }
        val date = photoDate.plusDays(offset)
        val timeM = HOUR_MINUTE_REGEX.find(cleaned)
        val hour = timeM?.groupValues?.get(1)?.toIntOrNull() ?: 12
        val minute = timeM?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return runCatching {
            date.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    /**
     * 日期行是否「带时刻」（「8月15日 10:34」/「昨天13:11」/「08-16 16:19」）：
     * 带时刻的日期行是某笔交易的支付时间，紧跟该笔（微信「商家/金额/日期」、支付宝条目内
     * 「商家/金额/…/日期」）→ 回填最近一笔；不带时刻的日期行（「8月13日」「昨天」「前天」）
     * 是支付宝分组标题，位于其下第一条目之前 → 存为 pendingDate 应用于下一笔。
     * 必须这样区分，否则支付宝「8月13日」分组标题会被当成条目日期丢弃/错配。
     */
    private fun isWechatDateLine(line: String): Boolean =
        WECHAT_DATE_LINE_REGEX.containsMatchIn(line)

    /**
     * 解析一张小票/账单中的多行明细（品名 + 金额 + 收支类型），用于「一张图多笔记账」。
     *
     * 支持两种布局：
     *  A. 列布局（微信/支付宝账单截图）：金额单独成列、商家单独成列，按顺序一一配对；
     *  B. 行布局（超市小票/外卖订单）：每行"品名 + 金额"。
     * 正负号决定收支：`+` → 收入，`-` → 支出，无符号 → 沿用整单默认类型。
     *
     * [photoTakenAt] 为图片上传/识别时刻：未识别到具体时间的条目以它为准，
     * 「今天/昨天/前天」等模糊时间词也相对它换算。
     */
    fun parseLineItems(
        text: String,
        type: String = ExtractedInfo.TYPE_EXPENSE,
        photoTakenAt: Long = System.currentTimeMillis(),
        alipay: Boolean = false,
    ): List<LineItem> {
        val lines = text.lines().map { normalizeLine(it) }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val photoDate = Instant.ofEpochMilli(photoTakenAt).atZone(ZoneId.systemDefault()).toLocalDate()

        // 支付宝账单条目是「商家+金额(同行混合) / 平台标识 / 分类 / 状态词 / 日期」结构，
        // 与微信的「商家块+金额块」列布局不同。若走 hasAmountColumn 的列布局检测，
        // 某些帧会被误判成"列布局"而用 金额[i]↔商家[i] 硬配对，OCR 漏读/多读一行即整体错位
        // （实测「分账-某平台=730」这种金额配错商家）。因此支付宝强制走交替布局（当前商家跟踪+金额配对），
        // 并先滤除状态词行（OCR 常把「芝麻免押下单成功」误读成「之麻先种下半成功」当商家）。
        if (alipay) {
            return parseAlipayLayout(lines, type, photoDate)
        }

        // 剥离支付宝账单页顶部汇总卡片里的孤立金额（¥80/¥830.00/¥1,306.87 等月度汇总数字），
        // 否则列布局会把这些汇总金额当成交易金额，导致后续所有配对错位。
        val cleaned = stripTopSummaryAmounts(lines, photoDate)
        if (cleaned.isEmpty()) return emptyList()

        // 是否存在"纯金额行"（如 +1.80 / -7.01）——微信/支付宝账单的列布局特征。
        // 放宽：仅 1 个纯金额行但存在商家行时也算列布局（支付宝单条账单「日期/商家/金额」分行）
        val pureAmountCount = cleaned.count { isPureAmountLine(it) }
        val merchantLineCount = cleaned.count { isMerchantLine(it, photoDate) }
        val hasAmountColumn = pureAmountCount >= 2 || (pureAmountCount >= 1 && merchantLineCount >= 1)
        if (!hasAmountColumn) return parseInlineLayout(cleaned, type, photoDate)
        // 区分两种列布局：
        //  - 交替布局（PaddleOCR 输出：商家、金额、日期按行交替）→ 按顺序配对，天然抗噪；
        //  - 分块布局（ML Kit 输出：商家日期一块 + 金额一块）→ 按序号一一配对。
        return if (isInterleavedLayout(cleaned, photoDate)) {
            parseInterleavedLayout(cleaned, type, photoDate)
        } else {
            parseColumnLayout(cleaned, type, photoDate)
        }
    }

    // 支付宝账单页顶部汇总卡片特征词（「我的消费图鉴」「收支分析」「本月已省」等卡片标题）
    private val SUMMARY_CARD_MARKERS = listOf(
        "我的消费图鉴", "收支分析", "本月已省", "今年累计已省", "累计已省", "消费图鉴",
    )

    /**
     * 剥离顶部汇总卡片区域内的孤立金额行：从第一个汇总卡片特征行开始，
     * 到第一个商家行之前，期间的所有纯金额行（月度汇总数字）直接丢弃，
     * 避免被列布局当成交易金额导致错位。特征行本身保留（会被 META 过滤）。
     */
    private fun stripTopSummaryAmounts(lines: List<String>, photoDate: LocalDate): List<String> {
        // 找到顶部汇总区起点（第一个特征行）
        var start = -1
        for (i in lines.indices) {
            if (SUMMARY_CARD_MARKERS.any { lines[i].contains(it) }) { start = i; break }
        }
        if (start < 0) return lines
        // 从特征行开始：丢弃纯金额行，直到遇到第一个真实商家行（第一笔交易）退出汇总区
        val out = mutableListOf<String>()
        var inZone = false
        for (i in lines.indices) {
            if (i == start) inZone = true
            if (inZone) {
                if (isMerchantLine(lines[i], photoDate)) {
                    inZone = false // 遇到第一笔真实交易，停止剥离
                } else if (isPureAmountLine(lines[i])) {
                    continue // 顶部汇总金额 → 丢弃
                }
            }
            out.add(lines[i])
        }
        return out
    }

    /**
     * 支付宝条目状态词独立行（OCR 常误读，如「之麻先种下半成功」「等待对方确认收货」）：
     * 这类行几乎不可能成为真实商家名，整行过滤，避免其被当成商家导致金额错配。
     *
     * 注意：真实商家「守约完成解冻押金」以「押金」结尾，不能因含「解冻」被误过滤，
     * 故只认「以成功结尾(且非押金)」「确认收货」「免押」等稳定状态特征。
     * 不含「关闭」——「交易关闭」是关闭标记，需留给 [isClosedNoteLine] 标记，不能在此滤掉。
     */
    private fun isAlipayStatusLine(line: String): Boolean {
        if (SIGNED_NUMBER_REGEX.containsMatchIn(line)) return false  // 含金额的混合行交给行内处理
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        if (cleaned.isEmpty()) return false
        if (cleaned.length > 12) return false
        // 以「成功」结尾（如「芝麻免押下单成功」被读成「之麻先种下半成功」）→ 状态行
        if (cleaned.endsWith("成功") && !cleaned.endsWith("押金")) return true
        if (cleaned.contains("确认收货")) return true
        if (cleaned.contains("免押")) return true
        return false
    }

    /**
     * 支付宝账单专用解析（与 [parseInterleavedLayout] 同思路，"当前商家跟踪 + 金额配对"），
     * 先滤除状态词行（避免「之麻先种下半成功」被当成商家造成金额错配），再走交替布局。
     *
     * 支付宝条目结构「商家+金额(同行混合) / 平台标识 / 分类 / 状态词 / 日期」，
     * 采用"商家行→金额行配对"自然吸收平台标识/分类等噪声行（它们无金额，不会被配对），
     * 不会出现列布局那种「金额[i]↔商家[i] 按序号硬配对」的错位。
     */
    private fun parseAlipayLayout(lines: List<String>, type: String, photoDate: LocalDate): List<LineItem> {
        val cleaned = stripTopSummaryAmounts(lines, photoDate)
        if (cleaned.isEmpty()) return emptyList()
        // 预滤状态词行：OCR 把「芝麻免押下单成功」等误读成别的"以成功结尾"整行时，会被当商家。
        val filtered = cleaned.filterNot { isAlipayStatusLine(it) }
        if (filtered.isEmpty()) return emptyList()
        return parseInterleavedLayout(filtered, type, photoDate)
    }

    /**
     * 是否"商家行紧跟金额行"交替布局（PaddleOCR 特征）。
     * 要求至少 2 个商家行后紧跟金额行，且占比 ≥ 一半；否则视为"商家块 + 金额块"
     * 的块布局（其最后一条商家恰好挨着第一个金额，仅 1 处衔接不算交替）。
     */
    private fun isInterleavedLayout(lines: List<String>, photoDate: LocalDate): Boolean {
        var merchantCount = 0
        var merchantThenAmount = 0
        for (i in 0 until lines.size - 1) {
            if (isMerchantLine(lines[i], photoDate)) {
                merchantCount++
                if (isPureAmountLine(lines[i + 1])) merchantThenAmount++
            }
        }
        return merchantThenAmount >= 2 && merchantThenAmount * 2 >= merchantCount
    }

    /** 该行是否"商家名"（非日期/金额/汇总/元信息/退款注释/支付宝状态词/界面噪声） */
    private fun isMerchantLine(line: String, photoDate: LocalDate): Boolean {
        if (SUMMARY_KEYWORDS.any { line.contains(it) }) return false
        if (META_KEYWORDS.any { line.contains(it) }) return false
        if (isUINoiseLine(line)) return false
        if (isRefundNoteLine(line)) return false
        if (isAwaitShipNoteLine(line)) return false
        if (isClosedNoteLine(line)) return false
        if (isPureAmountLine(line)) return false
        if (parseDateLine(line, LocalDate.now().year) != null) return false
        if (parseFuzzyDateLine(line, photoDate) != null) return false
        val name = extractItemName(line)
        return name.length >= 2 && name.any { it in '\u4e00'..'\u9fa5' }
    }

    /** 列布局：金额单独成列 + 商家单独成列，按顺序配对；解析每笔支付时间并按由近到远排序 */
    private fun parseColumnLayout(lines: List<String>, type: String, photoDate: LocalDate): List<LineItem> {
        val amounts = mutableListOf<SignedAmount>()
        val merchants = mutableListOf<String>()
        val dates = mutableListOf<Long>()

        val year = extractYear(lines) ?: LocalDate.now().year

        // 记录「已全额退款」注释行对应的被退款交易下标（= 最后一个金额的下标）
        val refundedIndexes = mutableSetOf<Int>()
        // 支付宝「交易关闭」对应的下标（扣款失败，未实际支出）
        val closedIndexes = mutableSetOf<Int>()

        for (line in lines) {
            if (SUMMARY_KEYWORDS.any { line.contains(it) }) continue
            if (META_KEYWORDS.any { line.contains(it) }) continue
            if (isUINoiseLine(line)) continue
            // 「已全额退款」是注释行：不入账，但把其上一笔交易标记为已退款。
            if (isRefundNoteLine(line)) {
                if (amounts.isNotEmpty()) refundedIndexes.add(amounts.size - 1)
                continue
            }
            // 支付宝「交易关闭」独立注释行：标记其上一笔为关闭（扣款失败）
            if (isClosedNoteLine(line)) {
                if (amounts.isNotEmpty()) closedIndexes.add(amounts.size - 1)
                continue
            }
            // 支付宝「等待确认收货」独立状态行：交易正常，跳过
            if (isAwaitShipNoteLine(line)) continue

            val date = parseDateLine(line, year) ?: parseFuzzyDateLine(line, photoDate)
            if (date != null) {
                dates.add(date)
                continue
            }

            if (isPureAmountLine(line)) {
                extractSignedAmount(line)?.let { amounts.add(it) }
                continue
            }
            // 混合行：商家 + 金额（+ 状态词）同一行（如「某某电商旗舰店 -59.90 交易关闭」）
            val inlineAmt = extractSignedAmount(line)
            val inlineName = extractItemName(line)
            if (inlineAmt != null && amountAtLineEnd(line) &&
                inlineName.length >= 2 && inlineName.any { it in '\u4e00'..'\u9fa5' }
            ) {
                amounts.add(inlineAmt)
                merchants.add(inlineName)
                if (line.contains(CLOSED_WORD)) closedIndexes.add(amounts.size - 1)
                continue
            }
            val name = extractItemName(line)
            if (name.length >= 2 && name.any { it in '\u4e00'..'\u9fa5' }) {
                merchants.add(name)
            }
        }

        val result = mutableListOf<LineItem>()
        val n = minOf(amounts.size, merchants.size)
        for (i in 0 until n) {
            val amt = amounts[i]
            val itemType = when (amt.sign) {
                1 -> ExtractedInfo.TYPE_INCOME
                -1 -> ExtractedInfo.TYPE_EXPENSE
                else -> type
            }
            val category = CategoryClassifier.classify(merchants[i], itemType, null)
            // 微信/支付宝账单里日期紧跟商家之后（按"商家、日期"交替输出），按序号一一配对；
            // 未配对到日期保持 null（不 fallback 到识别时刻，避免幽灵条目）
            val ts = dates.getOrNull(i)
            result.add(
                LineItem(
                    name = merchants[i],
                    amount = amt.amount,
                    category = category,
                    type = itemType,
                    timestamp = ts,
                    refunded = i in refundedIndexes,
                    closed = i in closedIndexes,
                    internal = isInternalTransfer(merchants[i]),
                )
            )
        }
        // 按支付时间由近到远排列
        return result.sortedByDescending { it.timestamp ?: 0L }
    }

    /**
     * 交替布局（PaddleOCR 输出）：商家、金额、日期按行交替（如「美团 / -7.01 / 8月15日」）。
     * 用「当前商家」跟踪最近一条商家名，遇金额即配对；噪声商家（图标文字/误读片段）
     * 会被下一条真商家覆盖，天然吸收，不产生假账、不串位。
     */
    private fun parseInterleavedLayout(lines: List<String>, type: String, photoDate: LocalDate): List<LineItem> {
        val year = extractYear(lines) ?: LocalDate.now().year
        val result = mutableListOf<LineItem>()
        var currentMerchant: String? = null
        // 前置日期（支付宝「昨天/前天」等分组标题在条目之前）：应用于下一条配对的金额
        var pendingDate: Long? = null

        for (line in lines) {
            if (SUMMARY_KEYWORDS.any { line.contains(it) }) continue
            if (META_KEYWORDS.any { line.contains(it) }) continue

            // 退款注释必须先于页签过滤：支付宝顶部也可能有单独「退款」标签，
            // 但它出现时 result 为空；交易后的「退款」则应标记最近一笔。
            if (isRefundNoteLine(line)) {
                if (result.isNotEmpty()) {
                    result[result.size - 1] = result[result.size - 1].copy(refunded = true)
                }
                continue
            }

            // 支付宝「交易关闭」独立注释行：标记最近一条为关闭（扣款失败）
            if (isClosedNoteLine(line)) {
                if (result.isNotEmpty()) {
                    result[result.size - 1] = result[result.size - 1].copy(closed = true)
                }
                continue
            }

            // 支付宝「等待确认收货」独立状态行：交易正常，跳过（照常录入）
            if (isAwaitShipNoteLine(line)) continue
            if (isUINoiseLine(line)) continue

            // 日期：微信「商家/金额/日期」中日期在后 → 回填最近一笔；
            // 支付宝「日期分组标题」在条目之前 → 存为 pendingDate 应用于下一笔。
            val date = parseDateLine(line, year) ?: parseFuzzyDateLine(line, photoDate)
            if (date != null) {
                if (isWechatDateLine(line)) {
                    // 微信：日期紧跟金额之后。只回填「还没有时间」的最近一笔；
                    // 帧首残留日期（属于上一帧最后一笔，result 为空或最近一笔已有时刻）直接丢弃，
                    // 绝不进入 pendingDate——否则会被下一笔金额消费，造成整体日期错位一天。
                    if (result.isNotEmpty() && result.last().timestamp == null) {
                        result[result.size - 1] = result.last().copy(timestamp = date)
                    }
                } else {
                    pendingDate = date
                }
                continue
            }

            // 金额：与当前商家配对
            if (isPureAmountLine(line)) {
                val amt = extractSignedAmount(line)
                if (amt != null && currentMerchant != null) {
                    val itemType = when (amt.sign) {
                        1 -> ExtractedInfo.TYPE_INCOME
                        -1 -> ExtractedInfo.TYPE_EXPENSE
                        else -> type
                    }
                    result.add(
                        LineItem(
                            name = currentMerchant!!,
                            amount = amt.amount,
                            category = CategoryClassifier.classify(currentMerchant!!, itemType, null),
                            type = itemType,
                            timestamp = pendingDate,
                            internal = isInternalTransfer(currentMerchant!!),
                        )
                    )
                    currentMerchant = null
                    pendingDate = null
                }
                continue
            }

            // 混合行：商家 + 金额（+ 状态词）同一行（如「某某电商旗舰店 -59.90 交易关闭」）
            val inlineAmt = extractSignedAmount(line)
            val inlineName = extractItemName(line)
            if (inlineAmt != null && amountAtLineEnd(line) &&
                inlineName.length >= 2 && inlineName.any { it in '\u4e00'..'\u9fa5' }
            ) {
                val itemType = when (inlineAmt.sign) {
                    1 -> ExtractedInfo.TYPE_INCOME
                    -1 -> ExtractedInfo.TYPE_EXPENSE
                    else -> type
                }
                result.add(
                    LineItem(
                        name = inlineName,
                        amount = inlineAmt.amount,
                        category = CategoryClassifier.classify(inlineName, itemType, null),
                        type = itemType,
                        timestamp = pendingDate,
                        closed = line.contains(CLOSED_WORD),
                        internal = isInternalTransfer(inlineName),
                    )
                )
                pendingDate = null
                continue
            }

            // 商家名
            val name = extractItemName(line)
            if (name.length >= 2 && name.any { it in '\u4e00'..'\u9fa5' }) {
                // OCR 常把「某某螺蛳粉 (大学城店)」拆成两行：括号续行应拼到当前商家，不覆盖。
                // 续行特征：以括号开头，或形如「分(大学城店)」（OCR 在括号前误加噪声字）。
                // 续行只取括号内的店名（如「大学城店」），丢弃括号与噪声前缀，保证与完整一行的
                // 「某某螺蛳粉 (大学城店)」合并后名字一致，跨帧去重才能命中。
                val isContinuation = isBracketContinuation(line)
                if (isContinuation && currentMerchant != null) {
                    val tail = bracketInner(line) ?: name
                    currentMerchant = "$currentMerchant $tail".trim()
                } else {
                    currentMerchant = name
                }
            }
        }

        // 未识别到日期的条目保持 null（不 fallback 到识别时刻，避免产生「幽灵条目」；
        // 跨帧去重会把它与同款真实条目合并，UI 保存时用整单时间兜底）
        return result.sortedByDescending { it.timestamp ?: 0L }
    }

    /** 是否「括号续行」：以 (（ 开头，或形如「分(大学城店)」——括号前是 1 个噪声字（分/收等） */
    private fun isBracketContinuation(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("(") || trimmed.startsWith("（")) return true
        if (trimmed.length <= 8) {
            val inner = bracketInner(trimmed)
            if (inner != null && inner.length >= 2 && inner.any { it in '\u4e00'..'\u9fa5' }) {
                // 「分(大学城店)」：括号前只允许 1 个非括号字符（噪声字）
                val prefix = trimmed.substringBefore("(").substringBefore("（").trim()
                return prefix.length <= 1
            }
        }
        return false
    }

    /** 提取括号内内容（「(大学城店)」→「大学城店」；无括号返回 null） */
    private fun bracketInner(line: String): String? {
        val m = BRACKET_INNER_REGEX.find(line) ?: return null
        val inner = m.groupValues[1].trim()
        return inner.ifEmpty { null }
    }

    /** 是否「已退款」注释行：独立一行、短、命中「退款」且不含金额 */
    private fun isRefundNoteLine(line: String): Boolean {
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        if (cleaned.isEmpty()) return false
        // 含金额的「退款」行是真实退款入账（如「某平台商户-退款 +7.90」），不是注释
        if (SIGNED_NUMBER_REGEX.containsMatchIn(line)) return false
        // 退款入账商家名通常较长（如「某平台商户-退款」9+ 字），注释行很短（≤8 字）
        return cleaned.length <= 8 && cleaned.contains(REFUND_NOTE_CORE)
    }

    /**
     * OCR 文本归一化：修复长截图降采样后常见的误识别，避免金额行被误判成非金额而丢弃。
     *  - 竖线 / 方括号等截图噪声 → 空格
     *  - 中文逗号/句号 → 半角（数字里的千分位/小数点）
     *  - 紧邻数字的「一」→ 负号「-」（微信账单的减号常被识别成「一」）
     *  - 字母 l/I 紧邻数字 → 1（如「l6」→「16」）
     */
    private fun normalizeLine(line: String): String {
        var s = line.trim()
        s = s.replace('|', ' ').replace('【', ' ').replace('】', ' ')
        s = s.replace('，', ',').replace('。', '.')
        s = YI_TO_MINUS_REGEX.replace(s, "-")
        s = L_TO_ONE_REGEX.replace(s, "1")
        return s.trim()
    }

    /** 行布局：每行"品名 + 金额" */
    private fun parseInlineLayout(lines: List<String>, type: String, photoDate: LocalDate): List<LineItem> {
        val year = extractYear(lines) ?: LocalDate.now().year
        val result = mutableListOf<LineItem>()
        for (line in lines) {
            if (SUMMARY_KEYWORDS.any { line.contains(it) }) continue
            if (META_KEYWORDS.any { line.contains(it) }) continue
            if (isUINoiseLine(line)) continue
            if (isAwaitShipNoteLine(line)) continue
            // 跳过日期行（含模糊词），防止「8月15日」被当成「品名 + 金额」
            if (parseDateLine(line, year) != null) continue
            if (parseFuzzyDateLine(line, photoDate) != null) continue

            val signed = extractSignedAmount(line) ?: continue
            val name = extractItemName(line)
            if (name.length < 2) continue

            val itemType = when (signed.sign) {
                1 -> ExtractedInfo.TYPE_INCOME
                -1 -> ExtractedInfo.TYPE_EXPENSE
                else -> type
            }
            val category = CategoryClassifier.classify("$name $line", itemType, null)
            result.add(
                LineItem(
                    name = name,
                    amount = signed.amount,
                    category = category,
                    type = itemType,
                    closed = line.contains(CLOSED_WORD),
                    internal = isInternalTransfer(name),
                )
            )
        }
        return result
    }

    /** 纯金额行：去掉金额相关字符后为空，且至少含一个数字（如 +1.80 / -7.01 / 3.50） */
    private fun isPureAmountLine(line: String): Boolean {
        val stripped = line.replace(PURE_AMOUNT_CHARS_REGEX, "")
        return stripped.isEmpty() && line.any { it.isDigit() }
    }

    /**
     * 行内金额是否位于行尾（或后面只剩「交易关闭/确认收货」等状态词）。
     * 用于区分「商家 + 金额」同行（真金额在行尾）与「商家名本身含数字」（如 7-11 便利店），
     * 避免把商家名里的数字误当金额。
     */
    private fun amountAtLineEnd(line: String): Boolean {
        val last = SIGNED_NUMBER_REGEX.findAll(line).lastOrNull() ?: return false
        val tail = line.substring(last.range.last + 1).trim()
        return tail.isEmpty() || tail.contains(CLOSED_WORD) || tail.contains(AWAIT_SHIP_WORD)
    }

    /**
     * 日期行解析：支持多种格式 → 时间戳；不是日期行返回 null。
     * 允许数字与「月/日」之间有空格（OCR 常把「8月6日」读成「8 月 6 日」，
     * 若不容忍会把「月 日」碎片当成商品名导入账单）。
     */
    private fun parseDateLine(line: String, year: Int): Long? {
        // 格式1：X月X日 [HH:mm]（微信，时刻可省略 → 12:00）
        val m1 = DATE_CN_REGEX.find(line)
        if (m1 != null) {
            val month = m1.groupValues[1].toIntOrNull() ?: return null
            val day = m1.groupValues[2].toIntOrNull() ?: return null
            val hour = m1.groupValues[3].toIntOrNull() ?: 12
            val minute = m1.groupValues[4].toIntOrNull() ?: 0
            return runCatching {
                LocalDateTime.of(year, month, day, hour, minute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }
        // 格式2：MM-dd HH:mm（支付宝，如「08-16 16:19」「08-1410:01」），要求带时刻避免与金额混淆
        val m2 = DATE_DASH_REGEX.find(line)
        if (m2 != null) {
            val month = m2.groupValues[1].toIntOrNull() ?: return null
            val day = m2.groupValues[2].toIntOrNull() ?: return null
            val hour = m2.groupValues[3].toIntOrNull() ?: return null
            val minute = m2.groupValues[4].toIntOrNull() ?: return null
            if (month in 1..12 && day in 1..31 && hour in 0..23 && minute in 0..59) {
                return runCatching {
                    LocalDateTime.of(year, month, day, hour, minute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
        }
        return null
    }

    /** 从文本中提取年份（如 "2026年8月" → 2026），找不到返回 null */
    private fun extractYear(lines: List<String>): Int? {
        for (line in lines) {
            YEAR_REGEX.find(line)?.let { return it.groupValues[1].toIntOrNull() }
        }
        return null
    }

    /**
     * 月份/年份标题行（月界噪声）：账单页顶部或滚动跨月时出现的「2026年8月」「8月」「2026年」
     * 这类独立标题行，不是交易，不该入账、也不该当商家。
     * 抖音/支付宝/微信的月界标题常带 OCR 尾巴（`2026年8月v`、`2026年7月√`、`8月>`），
     * 故先剥掉行尾的标题装饰符号再整行判断；日期行「8月15日」由 parseDateLine 处理，
     * 不会走到这里（整行不匹配「纯月」）。
     */
    private fun isMonthTitleLine(line: String): Boolean {
        var cleaned = line.replace(" ", "").replace("\u3000", "").trim()
        if (cleaned.isEmpty()) return false
        // 剥掉行尾/行首的 OCR 标题装饰（√ v ～ > ▼ ▽ ↓ · • 等），如「2026年8月v」→「2026年8月」
        cleaned = cleaned.trim { it in MONTH_TITLE_EDGE_CHARS }
        if (cleaned.isEmpty()) return false
        // 年月标题（2026年8月）
        if (MONTH_TITLE_REGEX.matches(cleaned)) return true
        // 纯年份（2026年）→ 月界顶部年份
        if (YEAR_ONLY_REGEX.matches(cleaned)) return true
        // 纯月份标签（8月）→ 月界标题/月份选择
        if (MONTH_ONLY_REGEX.matches(cleaned)) return true
        return false
    }

    /**
     * 非法「月=0 / 日=0」的月日噪声：OCR 常把微信账单页顶部「年份 + 月度总额」区域
     * 错读成「0月0日10.10」这样的行。月/日为 0 无法构成真实日期，parseDateLine 会失败，
     * 若不拦截会被后续混合行逻辑当成「商家(月 日) + 金额(10.10)」产出假账（实测「月日为账单」）。
     * 这里只要行内含「0月」（前面不是数字，避免误伤 10月）或「月X日里日以0开头且紧跟日」就整行当噪声。
     */
    private fun isInvalidMonthDayNoise(line: String): Boolean {
        val cleaned = line.replace(" ", "").replace("\u3000", "")
        if (cleaned.isEmpty()) return false
        return INVALID_ZERO_MONTH_REGEX.containsMatchIn(cleaned) ||
            INVALID_ZERO_DAY_REGEX.containsMatchIn(cleaned)
    }

    private data class SignedAmount(val amount: Double, val sign: Int)

    /**
     * 行内带符号金额：返回最可能是"金额"的数字与其符号（+1 收入 / -1 支出 / 0 无符号）。
     *
     * 优先级：带 ±/¥ 符号的（微信账单） > 带小数的（小票金额，避开容量/数量等整数） > 最大整数。
     */
    private fun extractSignedAmount(line: String): SignedAmount? {
        data class Cand(val value: Double, val sign: Int, val hasDecimal: Boolean, val hasSymbol: Boolean)

        val cands = SIGNED_NUMBER_REGEX.findAll(line).mapNotNull { m ->
            val signStr = m.groupValues[1]
            val sign = when (signStr) {
                "+" -> 1
                "-" -> -1
                else -> 0
            }
            val value = m.value
                .replace("¥", "").replace("￥", "").replace(",", "")
                .replace("+", "").replace("-", "").trim()
                .toDoubleOrNull()
            if (value == null || value !in 0.00..9_999_999.99) return@mapNotNull null
            Cand(
                value = value,
                sign = sign,
                hasDecimal = m.value.contains("."),
                hasSymbol = signStr.isNotEmpty() || m.value.contains("¥") || m.value.contains("￥"),
            )
        }.toList()
        if (cands.isEmpty()) return null

        val pool = when {
            cands.any { it.hasSymbol } -> cands.filter { it.hasSymbol }
            cands.any { it.hasDecimal } -> cands.filter { it.hasDecimal }
            else -> cands
        }
        val best = pool.maxByOrNull { it.value } ?: return null
        return SignedAmount(best.value, best.sign)
    }

    // 微信账单左侧图标被 OCR 误识别的常见单个汉字（极少作为真实商家名的首尾字）
    private val ICON_NOISE_CHARS = setOf(
        '门', '库', '亿', '砺', '世', '口', '心', '恩', '承', '宋', '众',
    )

    /** 去掉数字与常见符号，得到商品/项目名称，并清理行首/行尾图标噪声（保留连字符） */
    private fun extractItemName(line: String): String {
        val cleaned = line
            .replace(SIGNED_NUMBER_REGEX, " ")
            // 清洗支付宝状态词（「等待确认收货」「交易关闭」等），避免污染商家名
            .replace(CLOSED_WORD, " ")
            .replace("等待", " ")
            .replace(AWAIT_SHIP_WORD, " ")
            .replace(ITEM_NAME_PUNCTUATION_REGEX, " ")
            .replace(ITEM_NAME_UNIT_REGEX, " ")
        val tokens = cleaned.trim()
            .replace(MULTI_SPACE_REGEX, " ")
            .split(" ")
            .filter { it.isNotBlank() }

        // 去掉行首/行尾的孤立噪声 token（单字符 / 无中文 / 已知图标噪声字）
        var start = 0
        var end = tokens.size
        while (start < end && isNoiseToken(tokens[start])) start++
        while (end > start && isNoiseToken(tokens[end - 1])) end--
        val name = tokens.subList(start, end).joinToString(" ")
        return stripEdgeNoise(name).trim()
    }

    /** 单个 token 是否属于图标噪声 */
    private fun isNoiseToken(t: String): Boolean {
        if (t.isEmpty()) return true
        if (t.none { it in '\u4e00'..'\u9fa5' }) return true // 纯字母/数字/符号
        if (t.length == 1 && t[0] in ICON_NOISE_CHARS) return true
        return false
    }

    /** 去掉紧贴商家的单个图标噪声（如 "门美团" → "美团"、"美团门" → "美团"、"G美团" → "美团"） */
    private fun stripEdgeNoise(s: String): String {
        var r = s
        while (r.length > 1 && r.first() in ICON_NOISE_CHARS && r.drop(1).any { it in '\u4e00'..'\u9fa5' }) {
            r = r.drop(1)
        }
        while (r.length > 1 && r.last() in ICON_NOISE_CHARS && r.dropLast(1).any { it in '\u4e00'..'\u9fa5' }) {
            r = r.dropLast(1)
        }
        // 去掉首尾单个非中文、非连字符的图标噪声（如字母/数字）
        while (r.length > 1 && r.first() !in '\u4e00'..'\u9fa5' && r.first() != '-' && r.drop(1).any { it in '\u4e00'..'\u9fa5' }) {
            r = r.drop(1)
        }
        while (r.length > 1 && r.last() !in '\u4e00'..'\u9fa5' && r.last() != '-' && r.dropLast(1).any { it in '\u4e00'..'\u9fa5' }) {
            r = r.dropLast(1)
        }
        return r
    }
}

package com.smartledger.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 / 恢复功能检查：验证与 AppViewModel.backupTo / restoreFrom 相同的
 * JSON 序列化-反序列化往返逻辑，所有字段（含 null 图片、中文、特殊字符、大金额）无损。
 *
 * 说明：AppViewModel 依赖 Android 环境无法在 JVM 直接实例化，这里按相同逻辑
 * 模拟「备份生成 JSON → 恢复解析 JSON」，作为该功能的回归测试。
 */
class BackupRestoreTest {

    /** 模拟 backupTo：TransactionEntity → JSONObject（与 AppViewModel 相同写法） */
    private fun backup(t: TransactionEntity): JSONObject = JSONObject().apply {
        put("type", t.type)
        put("amount", t.amount)
        put("category", t.category)
        put("merchant", t.merchant)
        put("timestamp", t.timestamp)
        put("createdAt", t.createdAt)
        put("note", t.note)
        put("imageUri", t.imageUri ?: JSONObject.NULL)
        put("source", t.source)
    }

    /** 模拟 restoreFrom：JSONObject → TransactionEntity（与 AppViewModel 相同写法） */
    private fun restore(obj: JSONObject): TransactionEntity = TransactionEntity(
        type = obj.optString("type", TxType.EXPENSE),
        amount = obj.optDouble("amount", 0.0),
        category = obj.optString("category", "其他支出"),
        merchant = obj.optString("merchant", ""),
        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
        createdAt = obj.optLong("createdAt", obj.optLong("timestamp", 0L)),
        note = obj.optString("note", ""),
        imageUri = if (obj.isNull("imageUri")) null else obj.optString("imageUri"),
        source = obj.optString("source", TxSource.MANUAL),
    )

    @Test
    fun roundTripPreservesAllFields() {
        val originals = listOf(
            // 带 null 图片、中文、特殊字符、备注、OCR 来源
            TransactionEntity(
                id = 0,
                type = TxType.EXPENSE,
                amount = 1234.56,
                category = "餐饮",
                merchant = "美团 \"外卖\"\n分店·测试",
                timestamp = 1_786_800_000_000L,
                note = "备注：已全额退款",
                imageUri = null,
                source = TxSource.OCR,
            ),
            // 收入、无备注、带图片 uri
            TransactionEntity(
                id = 0,
                type = TxType.INCOME,
                amount = 0.1,
                category = "工资",
                merchant = "某某科技（广州）有限公司",
                timestamp = 1_786_800_000_001L,
                note = "",
                imageUri = "content://media/external/images/media/123",
                source = TxSource.MANUAL,
            ),
            // 大金额 + 小数精度
            TransactionEntity(
                id = 0,
                type = TxType.EXPENSE,
                amount = 98_765_432.10,
                category = "其他支出",
                merchant = "大额交易",
                timestamp = 1_786_800_000_002L,
                note = "",
                imageUri = null,
                source = TxSource.OCR,
            ),
        )

        // 备份：生成 JSON 文本（与 backupTo 一致：toString(2)）
        val array = JSONArray()
        originals.forEach { array.put(backup(it)) }
        val json = array.toString(2)
        assertTrue("备份 JSON 应包含中文", json.contains("美团"))
        assertTrue("备份 JSON 应含 imageUri 字段", json.contains("imageUri"))

        // 恢复：从 JSON 解析回实体（与 restoreFrom 一致）
        val parsed = JSONArray(json)
        assertEquals("条数往返一致", originals.size, parsed.length())

        originals.forEachIndexed { index, original ->
            val restored = restore(parsed.getJSONObject(index))
            assertEquals("type#$index", original.type, restored.type)
            assertEquals("amount#$index", original.amount, restored.amount, 0.0001)
            assertEquals("category#$index", original.category, restored.category)
            assertEquals("merchant#$index", original.merchant, restored.merchant)
            assertEquals("timestamp#$index", original.timestamp, restored.timestamp)
            assertEquals("createdAt#$index", original.createdAt, restored.createdAt)
            assertEquals("note#$index", original.note, restored.note)
            assertEquals("imageUri#$index", original.imageUri, restored.imageUri)
            assertEquals("source#$index", original.source, restored.source)
        }
    }

    @Test
    fun restoreHandlesMissingFields() {
        // 老版本备份可能缺字段：应有默认值兜底，不崩溃
        val obj = JSONObject().put("amount", 5.0)
        val t = restore(obj)
        assertEquals(TxType.EXPENSE, t.type)
        assertEquals(5.0, t.amount, 0.0001)
        assertEquals("其他支出", t.category)
        assertEquals("", t.merchant)
        assertEquals("", t.note)
        assertNull(t.imageUri)
        assertEquals(TxSource.MANUAL, t.source)
    }

    @Test
    fun restoreFallsBackCreatedAtToTimestamp() {
        // 老备份没有 createdAt 字段：回退为 timestamp，不崩溃
        val obj = JSONObject()
            .put("type", TxType.EXPENSE)
            .put("amount", 5.0)
            .put("timestamp", 1_234_567_890_000L)
        val t = restore(obj)
        assertEquals(1_234_567_890_000L, t.createdAt)
    }

    @Test
    fun imageUriNullNeverBecomesStringNull() {
        // 关键回归：imageUri 为 null 时备份写 JSONObject.NULL，
        // 恢复必须还原为 null 而不是字符串 "null"
        val entity = TransactionEntity(imageUri = null)
        val json = backup(entity).toString()
        assertTrue(json.contains("null"))
        val restored = restore(JSONObject(json))
        assertNull("imageUri 不应变成字符串 \"null\"", restored.imageUri)
    }
}

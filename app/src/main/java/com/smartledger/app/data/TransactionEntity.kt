package com.smartledger.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一笔记账记录
 *
 * @param type     交易类型：TxType.EXPENSE（支出）/ TxType.INCOME（收入）
 * @param amount   金额（元）
 * @param category 自动归类后的消费类型，如"餐饮"、"工资"
 * @param merchant 商家 / 收款方名称
 * @param timestamp 交易发生时间（epoch 毫秒）
 * @param createdAt 本条记录进入系统的时间（epoch 毫秒）——总金额/预算只统计
 *                  设置时刻之后进入系统的账单，判断依据是 createdAt 而非交易时间戳
 * @param note     备注
 * @param imageUri 原始凭证图片 uri（第一张）
 * @param source   TxSource.OCR（图片识别） / TxSource.MANUAL（手动录入）
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = TxType.EXPENSE,
    val amount: Double = 0.0,
    val category: String = "其他支出",
    val merchant: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val imageUri: String? = null,
    val source: String = TxSource.MANUAL,
)

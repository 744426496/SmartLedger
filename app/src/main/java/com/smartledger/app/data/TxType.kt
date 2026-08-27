package com.smartledger.app.data

/** 交易类型常量 */
object TxType {
    const val EXPENSE = "expense"
    const val INCOME = "income"

    fun label(type: String): String = if (type == INCOME) "收入" else "支出"
}

/** 记账来源 */
object TxSource {
    const val OCR = "ocr"
    const val MANUAL = "manual"
}

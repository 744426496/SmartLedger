package com.smartledger.app.data

import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val dao: TransactionDao) {

    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    suspend fun insert(transaction: TransactionEntity): Long = dao.insert(transaction)

    suspend fun getAll(): List<TransactionEntity> = dao.getAll()

    suspend fun insertAll(transactions: List<TransactionEntity>) = dao.insertAll(transactions)

    suspend fun clearAll() = dao.clearAll()

    suspend fun update(transaction: TransactionEntity) = dao.update(transaction)

    suspend fun delete(transaction: TransactionEntity) = dao.delete(transaction)

    suspend fun deleteAll(transactions: List<TransactionEntity>) = dao.deleteAll(transactions)

    suspend fun getBetween(start: Long, end: Long): List<TransactionEntity> =
        dao.getBetween(start, end)

    suspend fun updateCategoryName(oldName: String, newName: String) =
        dao.updateCategoryName(oldName, newName)
}

package com.momentledger.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query(
        """
        SELECT * FROM transactions
        WHERE localDate BETWEEN :startDate AND :endDate
        ORDER BY timestampMillis DESC, id DESC
        """
    )
    fun observeInDateRange(startDate: String, endDate: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestampMillis DESC, id DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE timestampMillis BETWEEN :startMillis AND :endMillis
        ORDER BY timestampMillis ASC, id ASC
        """
    )
    suspend fun getInTimestampRange(
        startMillis: Long,
        endMillis: Long
    ): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE externalOrderId = :orderId LIMIT 1")
    suspend fun findByExternalOrderId(orderId: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TransactionEntity>)

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query(
        """
        UPDATE transactions
        SET excludedFromStats = :excluded,
            updatedAtMillis = :updatedAtMillis
        WHERE id = :id
        """
    )
    suspend fun updateExcludedFromStats(
        id: Long,
        excluded: Boolean,
        updatedAtMillis: Long
    )

    @Query(
        """
        UPDATE transactions
        SET categoryId = :newCategoryId,
            updatedAtMillis = :updatedAtMillis
        WHERE categoryId = :oldCategoryId
        """
    )
    suspend fun reassignCategory(
        oldCategoryId: String,
        newCategoryId: String,
        updatedAtMillis: Long
    )

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

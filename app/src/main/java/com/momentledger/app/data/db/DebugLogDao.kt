package com.momentledger.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DebugLogDao {

    @Query(
        """
        SELECT * FROM capture_debug_logs
        ORDER BY timestampMillis DESC, id DESC
        LIMIT :limit
        """
    )
    abstract fun observeLatest(limit: Int = 50): Flow<List<DebugLogEntity>>

    @Insert
    abstract suspend fun insert(entity: DebugLogEntity): Long

    @Query(
        """
        DELETE FROM capture_debug_logs
        WHERE id NOT IN (
            SELECT id FROM capture_debug_logs
            ORDER BY timestampMillis DESC, id DESC
            LIMIT :keepCount
        )
        """
    )
    abstract suspend fun trimToLatest(keepCount: Int = 50)

    @Transaction
    open suspend fun insertAndTrim(entity: DebugLogEntity, keepCount: Int = 50): Long {
        val id = insert(entity)
        trimToLatest(keepCount)
        return id
    }

    @Query("DELETE FROM capture_debug_logs")
    abstract suspend fun deleteAll()
}

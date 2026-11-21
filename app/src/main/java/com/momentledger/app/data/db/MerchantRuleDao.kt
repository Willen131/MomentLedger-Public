package com.momentledger.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverters
import androidx.room.Update
import com.momentledger.app.domain.model.CategoryType

@Dao
@TypeConverters(LedgerConverters::class)
interface MerchantRuleDao {

    @Query(
        """
        SELECT * FROM merchant_category_rules
        ORDER BY matchType ASC, priority DESC, id DESC
        """
    )
    suspend fun getAll(): List<MerchantRuleEntity>

    @Query(
        """
        SELECT * FROM merchant_category_rules
        WHERE categoryType = :categoryType AND matchType = 'EXACT_USER'
        ORDER BY priority DESC, id DESC
        """
    )
    suspend fun getExactUserRulesByType(categoryType: CategoryType): List<MerchantRuleEntity>

    @Query("SELECT COUNT(*) FROM merchant_category_rules")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: MerchantRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MerchantRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(entities: List<MerchantRuleEntity>)

    @Update
    suspend fun update(entity: MerchantRuleEntity)

    @Query(
        """
        UPDATE merchant_category_rules
        SET categoryId = :newCategoryId
        WHERE categoryId = :oldCategoryId
        """
    )
    suspend fun reassignCategory(oldCategoryId: String, newCategoryId: String)

    @Query("DELETE FROM merchant_category_rules")
    suspend fun deleteAll()
}

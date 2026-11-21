package com.momentledger.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import com.momentledger.app.domain.model.CategoryType
import kotlinx.coroutines.flow.Flow

@Dao
@TypeConverters(LedgerConverters::class)
abstract class CategoryDao {

    @Query("SELECT * FROM categories ORDER BY categoryType ASC, sortOrder ASC, id ASC")
    abstract fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY categoryType ASC, sortOrder ASC, id ASC")
    abstract suspend fun getAll(): List<CategoryEntity>

    @Query(
        """
        SELECT * FROM categories
        WHERE categoryType = :type
        ORDER BY sortOrder ASC, id ASC
        """
    )
    abstract suspend fun getByType(type: CategoryType): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnoreAll(categories: List<CategoryEntity>)

    @Query("UPDATE categories SET sortOrder = :sortOrder WHERE id = :id")
    abstract suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM categories WHERE id = :id AND isSystem = 0")
    abstract suspend fun deleteCustomById(id: String)

    @Query(
        """
        UPDATE transactions
        SET categoryId = :newCategoryId,
            updatedAtMillis = :updatedAtMillis
        WHERE categoryId = :oldCategoryId
        """
    )
    abstract suspend fun reassignTransactionCategories(
        oldCategoryId: String,
        newCategoryId: String,
        updatedAtMillis: Long
    )

    @Query(
        """
        UPDATE merchant_category_rules
        SET categoryId = :newCategoryId
        WHERE categoryId = :oldCategoryId
        """
    )
    abstract suspend fun reassignRuleCategories(
        oldCategoryId: String,
        newCategoryId: String
    )

    @Query("DELETE FROM categories")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun deleteCustomCategoryAtomically(
        categoryId: String,
        fallbackCategoryId: String,
        updatedAtMillis: Long
    ) {
        val category = getById(categoryId) ?: return
        if (category.isSystem) return

        reassignTransactionCategories(categoryId, fallbackCategoryId, updatedAtMillis)
        reassignRuleCategories(categoryId, fallbackCategoryId)
        deleteCustomById(categoryId)
    }

    @Transaction
    open suspend fun updateSortOrders(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id ->
            updateSortOrder(id, index + 1)
        }
    }
}

package com.momentledger.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.momentledger.app.domain.model.DefaultCategories

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        MerchantRuleEntity::class,
        DebugLogEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(LedgerConverters::class)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun debugLogDao(): DebugLogDao

    companion object {
        private const val DATABASE_NAME = "moment_ledger.db"

        @Volatile
        private var instance: LedgerDatabase? = null

        fun getInstance(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }

        private fun buildDatabase(appContext: Context): LedgerDatabase =
            Room.databaseBuilder(
                appContext,
                LedgerDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(PrepopulateCallback)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN customChannel TEXT")
                db.execSQL("ALTER TABLE categories ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            }
        }

        private object PrepopulateCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.beginTransaction()
                try {
                    for (category in DefaultCategories.all) {
                        val values = ContentValues().apply {
                            put("id", category.id)
                            put("name", category.name)
                            put("categoryType", category.categoryType.name)
                            put("iconKey", category.iconKey)
                            put("colorHex", category.colorHex)
                            put("sortOrder", category.sortOrder)
                            put("isSystem", if (category.isSystem) 1 else 0)
                        }
                        db.insert("categories", SQLiteDatabase.CONFLICT_IGNORE, values)
                    }

                    for (rule in DefaultCategories.builtInRules) {
                        val values = ContentValues().apply {
                            put("id", rule.id)
                            put("pattern", rule.pattern)
                            put("categoryType", rule.categoryType.name)
                            put("matchType", rule.matchType.name)
                            put("categoryId", rule.categoryId)
                            put("priority", rule.priority)
                        }
                        db.insert("merchant_category_rules", SQLiteDatabase.CONFLICT_IGNORE, values)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }
}

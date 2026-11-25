package com.momentledger.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.momentledger.app.domain.model.CaptureDebugLog
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType

class LedgerConverters {

    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType =
        runCatching { TransactionType.valueOf(value) }.getOrDefault(TransactionType.EXPENSE)

    @TypeConverter
    fun fromPaymentChannel(value: PaymentChannel): String = value.name

    @TypeConverter
    fun toPaymentChannel(value: String): PaymentChannel =
        runCatching { PaymentChannel.valueOf(value) }.getOrDefault(PaymentChannel.MANUAL)

    @TypeConverter
    fun fromCaptureSource(value: CaptureSource): String = value.name

    @TypeConverter
    fun toCaptureSource(value: String): CaptureSource =
        runCatching { CaptureSource.valueOf(value) }.getOrDefault(CaptureSource.MANUAL)

    @TypeConverter
    fun fromCategoryType(value: CategoryType): String = value.name

    @TypeConverter
    fun toCategoryType(value: String): CategoryType =
        runCatching { CategoryType.valueOf(value) }.getOrDefault(CategoryType.EXPENSE)

    @TypeConverter
    fun fromRuleMatchType(value: RuleMatchType): String = value.name

    @TypeConverter
    fun toRuleMatchType(value: String): RuleMatchType =
        runCatching { RuleMatchType.valueOf(value) }.getOrDefault(RuleMatchType.KEYWORD_BUILTIN)
}

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["timestampMillis"]),
        Index(value = ["localDate"]),
        Index(value = ["hourOfDay"]),
        Index(value = ["externalOrderId"], unique = true)
    ]
)
@TypeConverters(LedgerConverters::class)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMillis: Long,
    val localDate: String,
    val hourOfDay: Int,
    val amountCents: Long,
    val type: TransactionType,
    val channel: PaymentChannel,
    val fundingMethod: String,
    val merchant: String,
    val categoryId: String,
    val note: String,
    val excludedFromStats: Boolean,
    val captureSource: CaptureSource,
    val externalOrderId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val customChannel: String? = null
)

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["categoryType", "sortOrder"])
    ]
)
@TypeConverters(LedgerConverters::class)
data class CategoryEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val categoryType: CategoryType,
    val iconKey: String,
    val colorHex: String,
    val sortOrder: Int,
    val isSystem: Boolean,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false
)

@Entity(
    tableName = "merchant_category_rules",
    indices = [
        Index(value = ["categoryType"]),
        Index(value = ["categoryId"])
    ]
)
@TypeConverters(LedgerConverters::class)
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val pattern: String,
    val categoryType: CategoryType,
    val matchType: RuleMatchType,
    val categoryId: String,
    val priority: Int
)

@Entity(
    tableName = "capture_debug_logs",
    indices = [
        Index(value = ["timestampMillis"])
    ]
)
data class DebugLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMillis: Long,
    val source: String,
    val packageName: String,
    val matched: Boolean,
    val summary: String
)

fun TransactionEntity.toDomain(): TransactionRecord = TransactionRecord(
    id = id,
    timestampMillis = timestampMillis,
    localDate = localDate,
    hourOfDay = hourOfDay,
    amountCents = amountCents,
    type = type,
    channel = channel,
    fundingMethod = fundingMethod,
    merchant = merchant,
    categoryId = categoryId,
    note = note,
    excludedFromStats = excludedFromStats,
    captureSource = captureSource,
    externalOrderId = externalOrderId.normalizedExternalOrderId(),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    customChannel = customChannel
)

fun TransactionRecord.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    timestampMillis = timestampMillis,
    localDate = localDate,
    hourOfDay = hourOfDay,
    amountCents = amountCents,
    type = type,
    channel = channel,
    fundingMethod = fundingMethod,
    merchant = merchant,
    categoryId = categoryId,
    note = note,
    excludedFromStats = excludedFromStats,
    captureSource = captureSource,
    externalOrderId = externalOrderId.normalizedExternalOrderId(),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    customChannel = customChannel
)

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    categoryType = categoryType,
    iconKey = iconKey,
    colorHex = colorHex,
    sortOrder = sortOrder,
    isSystem = isSystem,
    isArchived = isArchived
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    categoryType = categoryType,
    iconKey = iconKey,
    colorHex = colorHex,
    sortOrder = sortOrder,
    isSystem = isSystem,
    isArchived = isArchived
)

fun MerchantRuleEntity.toDomain(): MerchantRule = MerchantRule(
    id = id,
    pattern = pattern,
    categoryType = categoryType,
    matchType = matchType,
    categoryId = categoryId,
    priority = priority
)

fun MerchantRule.toEntity(): MerchantRuleEntity = MerchantRuleEntity(
    id = id,
    pattern = pattern,
    categoryType = categoryType,
    matchType = matchType,
    categoryId = categoryId,
    priority = priority
)

fun DebugLogEntity.toDomain(): CaptureDebugLog = CaptureDebugLog(
    id = id,
    timestampMillis = timestampMillis,
    source = source,
    packageName = packageName,
    matched = matched,
    summary = summary
)

fun CaptureDebugLog.toEntity(): DebugLogEntity = DebugLogEntity(
    id = id,
    timestampMillis = timestampMillis,
    source = source,
    packageName = packageName,
    matched = matched,
    summary = summary
)

private fun String?.normalizedExternalOrderId(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }

package com.momentledger.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.momentledger.app.domain.engine.BudgetSettings
import com.momentledger.app.domain.engine.AnalyticsCalculator
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TimePeriod
import com.momentledger.app.domain.model.areValidTimePeriods
import com.momentledger.app.domain.model.defaultTimePeriods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.Month

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

class LedgerPreferences(
    private val prefs: SharedPreferences,
    today: () -> LocalDate = LocalDate::now
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _themePreference = MutableStateFlow(readThemePreference())
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    private val _quickNotificationEnabled = MutableStateFlow(readQuickNotificationEnabled())
    val quickNotificationEnabled: StateFlow<Boolean> = _quickNotificationEnabled.asStateFlow()

    private val _timePeriods = MutableStateFlow(readTimePeriods())
    val timePeriods: StateFlow<List<TimePeriod>> = _timePeriods.asStateFlow()

    private val _budget = MutableStateFlow(readBudget(today))
    val budget = _budget.asStateFlow()
    private val _customChannels = MutableStateFlow(readJson<List<String>>("custom_channels").orEmpty())
    val customChannels = _customChannels.asStateFlow()
    private val _hiddenChannels = MutableStateFlow(readJson<Set<PaymentChannel>>("hidden_channels").orEmpty())
    val hiddenChannels = _hiddenChannels.asStateFlow()

    fun setBudget(settings: BudgetSettings) {
        require(settings.isValid)
        prefs.edit().putString(KEY_BUDGET, Json.encodeToString(settings)).apply()
        _budget.value = settings
    }

    private fun readBudget(today: () -> LocalDate): BudgetSettings {
        if (prefs.contains(KEY_BUDGET)) {
            return readJson<BudgetSettings>(KEY_BUDGET)?.takeIf { it.isValid } ?: BudgetSettings()
        }
        val previous = runCatching {
            prefs.getString("budget", null)?.let { legacyBudgetJson.decodeFromString<LegacyBudgetSettings>(it) }
        }.getOrNull() ?: return BudgetSettings()
        val yearMonth = Month.of(previous.yearStartMonth.coerceIn(1, 12))
        val migrated = BudgetSettings(
            startDay = previous.startDay.coerceIn(1, 31),
            totalCents = previous.totalCents?.takeIf { it > 0 },
            dailyCents = previous.daily.totalCents?.takeIf { it > 0 }
                ?.let { mapOf(today().toString() to it) }.orEmpty(),
            yearlyCents = previous.yearly.totalCents?.takeIf { it > 0 },
            yearStartMonth = yearMonth.value,
            yearStartDay = previous.yearStartDay.coerceIn(1, yearMonth.maxLength())
        )
        prefs.edit().putString(KEY_BUDGET, Json.encodeToString(migrated)).apply()
        return migrated
    }

    fun deleteChannel(channel: PaymentChannel, customName: String? = null) {
        if (customName != null) {
            val updated = _customChannels.value.filterNot { it == customName }
            prefs.edit().putString("custom_channels", Json.encodeToString(updated)).apply()
            _customChannels.value = updated
        } else {
            val updated = _hiddenChannels.value + channel
            prefs.edit().putString("hidden_channels", Json.encodeToString(updated)).apply()
            _hiddenChannels.value = updated
        }
    }

    fun restoreDefaultChannels() {
        prefs.edit().remove("hidden_channels").apply()
        _hiddenChannels.value = emptySet()
    }

    fun addCustomChannel(raw: String): String {
        val name = raw.trim()
        require(name.length in 1..24 && name.none { it.isISOControl() }) { "请输入 1–24 个字符的渠道名称" }
        require(name.first() !in "=+-@") { "渠道名称请以文字或数字开头" }
        val reserved = PaymentChannel.entries.map(AnalyticsCalculator::channelLabel) + listOf("微信", "手动", "银行")
        require(reserved.none { it.equals(name, ignoreCase = true) }) { "已存在同名支付渠道" }
        _customChannels.value.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        val updated = _customChannels.value + name
        prefs.edit().putString("custom_channels", Json.encodeToString(updated)).apply()
        _customChannels.value = updated
        return name
    }

    private inline fun <reified T> readJson(key: String): T? = runCatching {
        prefs.getString(key, null)?.let { Json.decodeFromString<T>(it) }
    }.getOrNull()

    fun setTimePeriods(periods: List<TimePeriod>) {
        val saved = periods.map { it.copy(label = it.label.trim()) }
        require(areValidTimePeriods(saved))
        prefs.edit().putString(KEY_TIME_PERIODS, Json.encodeToString(saved)).apply()
        _timePeriods.value = saved
    }

    private fun readTimePeriods(): List<TimePeriod> = runCatching {
        prefs.getString(KEY_TIME_PERIODS, null)?.let { Json.decodeFromString<List<TimePeriod>>(it) }
    }.getOrNull()?.takeIf(::areValidTimePeriods) ?: defaultTimePeriods

    fun setThemePreference(preference: ThemePreference) {
        prefs.edit().putString(KEY_THEME_PREFERENCE, preference.name).apply()
        _themePreference.value = preference
    }

    fun setQuickNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_NOTIFICATION_ENABLED, enabled).apply()
        _quickNotificationEnabled.value = enabled
    }

    private fun readThemePreference(): ThemePreference {
        val raw = prefs.getString(KEY_THEME_PREFERENCE, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(raw) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    private fun readQuickNotificationEnabled(): Boolean =
        prefs.getBoolean(KEY_QUICK_NOTIFICATION_ENABLED, true)

    companion object {
        private const val PREFS_NAME = "moment_ledger_prefs"
        private const val KEY_THEME_PREFERENCE = "theme_preference"
        private const val KEY_QUICK_NOTIFICATION_ENABLED = "quick_notification_enabled"
        private const val KEY_TIME_PERIODS = "time_periods"
        private const val KEY_BUDGET = "budget_v2"
        private val legacyBudgetJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class LegacyBudgetSettings(
    val startDay: Int = 1,
    val totalCents: Long? = null,
    val daily: LegacyBudgetAmount = LegacyBudgetAmount(),
    val yearly: LegacyBudgetAmount = LegacyBudgetAmount(),
    val yearStartMonth: Int = 1,
    val yearStartDay: Int = 1
)

@Serializable
private data class LegacyBudgetAmount(val totalCents: Long? = null)

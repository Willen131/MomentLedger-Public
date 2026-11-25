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

internal data class OverlayAppearance(
    val scale: Float = 1f,
    val menuScale: Float = 1f,
    val opacity: Float = 1f,
    val motion: Boolean = true,
    val dockedMotion: Boolean = false,
    val physicsIntensity: Float = 1f,
    val physicsResponsiveness: Float = 1f,
    val tapHaptic: Boolean = true,
    val tapHapticStrength: Float = 0.50f
) {
    companion object {
        const val MIN_SCALE = 0.55f
        const val MAX_SCALE = 1.75f
        const val MIN_MENU_SCALE = 0.50f
        const val MAX_MENU_SCALE = 1.50f
        const val MIN_OPACITY = 0.30f
        const val MIN_PHYSICS_INTENSITY = 0.40f
        const val MAX_PHYSICS_INTENSITY = 1.60f
        const val MIN_PHYSICS_RESPONSIVENESS = 0.50f
        const val MAX_PHYSICS_RESPONSIVENESS = 1.60f
        const val MIN_TAP_HAPTIC_STRENGTH = 0.00f
        const val MAX_TAP_HAPTIC_STRENGTH = 1.00f
    }
}

class LedgerPreferences(
    private val prefs: SharedPreferences,
    private val todayProvider: () -> LocalDate = LocalDate::now
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val writeLock = Any()

    private val _themePreference = MutableStateFlow(readThemePreference())
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    private val _quickNotificationEnabled = MutableStateFlow(readQuickNotificationEnabled())
    val quickNotificationEnabled: StateFlow<Boolean> = _quickNotificationEnabled.asStateFlow()

    private val _quickNotificationExpanded = MutableStateFlow(readQuickNotificationExpanded())
    val quickNotificationExpanded: StateFlow<Boolean> = _quickNotificationExpanded.asStateFlow()

    private val _screenCaptureEnabled = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_CAPTURE_ENABLED, false))
    val screenCaptureEnabled: StateFlow<Boolean> = _screenCaptureEnabled.asStateFlow()

    private val _excludeFromRecents = MutableStateFlow(prefs.getBoolean(KEY_EXCLUDE_FROM_RECENTS, false))
    val excludeFromRecents: StateFlow<Boolean> = _excludeFromRecents.asStateFlow()

    private val _overlayMode = MutableStateFlow(readOverlayMode())
    val overlayMode: StateFlow<String> = _overlayMode.asStateFlow()

    private val _overlayPet = MutableStateFlow(readOverlayPet())
    val overlayPet: StateFlow<String> = _overlayPet.asStateFlow()

    private val _bubbleAppearance = MutableStateFlow(readOverlayAppearance(isPet = false))
    internal val bubbleAppearance: StateFlow<OverlayAppearance> = _bubbleAppearance.asStateFlow()

    private val _petAppearance = MutableStateFlow(readOverlayAppearance(isPet = true))
    internal val petAppearance: StateFlow<OverlayAppearance> = _petAppearance.asStateFlow()

    private val _timePeriods = MutableStateFlow(readTimePeriods())
    val timePeriods: StateFlow<List<TimePeriod>> = _timePeriods.asStateFlow()

    private val _budget = MutableStateFlow(readBudget(todayProvider))
    val budget = _budget.asStateFlow()
    private val _customChannels = MutableStateFlow(readJson<List<String>>("custom_channels").orEmpty())
    val customChannels = _customChannels.asStateFlow()
    private val _hiddenChannels = MutableStateFlow(readJson<Set<PaymentChannel>>("hidden_channels").orEmpty())
    val hiddenChannels = _hiddenChannels.asStateFlow()

    fun setBudget(settings: BudgetSettings) {
        require(settings.isValid)
        val pruned = pruneOldDailyBudgets(settings, todayProvider())
        synchronized(writeLock) {
            prefs.edit().putString(KEY_BUDGET, prefJson.encodeToString(pruned)).apply()
            _budget.value = pruned
        }
    }

    private fun readBudget(today: () -> LocalDate): BudgetSettings {
        if (prefs.contains(KEY_BUDGET)) {
            val loaded = readJson<BudgetSettings>(KEY_BUDGET)?.takeIf { it.isValid } ?: return BudgetSettings()
            val pruned = pruneOldDailyBudgets(loaded, today())
            if (pruned != loaded) {
                prefs.edit().putString(KEY_BUDGET, prefJson.encodeToString(pruned)).apply()
            }
            return pruned
        }
        val previous = runCatching {
            prefs.getString("budget", null)?.let { prefJson.decodeFromString<LegacyBudgetSettings>(it) }
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
        prefs.edit().putString(KEY_BUDGET, prefJson.encodeToString(migrated)).apply()
        return migrated
    }

    private fun pruneOldDailyBudgets(settings: BudgetSettings, today: LocalDate): BudgetSettings {
        if (settings.dailyCents.isEmpty()) return settings
        val cutoff = today.minusDays(DAILY_BUDGET_RETENTION_DAYS)
        val kept = settings.dailyCents.filterKeys { key ->
            val parsed = runCatching { LocalDate.parse(key) }.getOrNull()
            parsed != null && !parsed.isBefore(cutoff)
        }
        return if (kept.size == settings.dailyCents.size) settings else settings.copy(dailyCents = kept)
    }

    fun deleteChannel(channel: PaymentChannel, customName: String? = null) {
        synchronized(writeLock) {
            if (customName != null) {
                val updated = _customChannels.value.filterNot { it == customName }
                prefs.edit().putString("custom_channels", prefJson.encodeToString(updated)).apply()
                _customChannels.value = updated
            } else {
                val updated = _hiddenChannels.value + channel
                prefs.edit().putString("hidden_channels", prefJson.encodeToString(updated)).apply()
                _hiddenChannels.value = updated
            }
        }
    }

    fun restoreDefaultChannels() {
        synchronized(writeLock) {
            prefs.edit().remove("hidden_channels").apply()
            _hiddenChannels.value = emptySet()
        }
    }

    fun addCustomChannel(raw: String): String {
        val name = raw.trim()
        require(name.length in 1..24 && name.none { it.isISOControl() }) { "请输入 1–24 个字符的渠道名称" }
        require(name.first() !in "=+-@") { "渠道名称请以文字或数字开头" }
        val reserved = PaymentChannel.entries.map(AnalyticsCalculator::channelLabel) + listOf("微信", "手动", "银行")
        require(reserved.none { it.equals(name, ignoreCase = true) }) { "已存在同名支付渠道" }
        synchronized(writeLock) {
            _customChannels.value.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
            val updated = _customChannels.value + name
            prefs.edit().putString("custom_channels", prefJson.encodeToString(updated)).apply()
            _customChannels.value = updated
            return name
        }
    }

    private inline fun <reified T> readJson(key: String): T? = runCatching {
        prefs.getString(key, null)?.let { prefJson.decodeFromString<T>(it) }
    }.getOrNull()

    fun setTimePeriods(periods: List<TimePeriod>) {
        val saved = periods.map { it.copy(label = it.label.trim()) }
        require(areValidTimePeriods(saved))
        synchronized(writeLock) {
            prefs.edit().putString(KEY_TIME_PERIODS, prefJson.encodeToString(saved)).apply()
            _timePeriods.value = saved
        }
    }

    private fun readTimePeriods(): List<TimePeriod> = runCatching {
        prefs.getString(KEY_TIME_PERIODS, null)?.let { prefJson.decodeFromString<List<TimePeriod>>(it) }
    }.getOrNull()?.takeIf(::areValidTimePeriods) ?: defaultTimePeriods

    fun setThemePreference(preference: ThemePreference) {
        synchronized(writeLock) {
            prefs.edit().putString(KEY_THEME_PREFERENCE, preference.name).apply()
            _themePreference.value = preference
        }
    }

    fun setQuickNotificationEnabled(enabled: Boolean) {
        synchronized(writeLock) {
            prefs.edit().putBoolean(KEY_QUICK_NOTIFICATION_ENABLED, enabled).apply()
            _quickNotificationEnabled.value = enabled
        }
    }

    fun setQuickNotificationExpanded(expanded: Boolean) {
        synchronized(writeLock) {
            prefs.edit().putBoolean(KEY_QUICK_NOTIFICATION_EXPANDED, expanded).commit()
            _quickNotificationExpanded.value = expanded
        }
    }

    fun setScreenCaptureEnabled(enabled: Boolean) {
        synchronized(writeLock) {
            prefs.edit().putBoolean(KEY_SCREEN_CAPTURE_ENABLED, enabled).commit()
            _screenCaptureEnabled.value = enabled
        }
    }

    fun setExcludeFromRecents(exclude: Boolean) {
        synchronized(writeLock) {
            prefs.edit().putBoolean(KEY_EXCLUDE_FROM_RECENTS, exclude).commit()
            _excludeFromRecents.value = exclude
        }
    }

    fun setOverlayMode(mode: String) {
        synchronized(writeLock) {
            prefs.edit().putString(KEY_OVERLAY_MODE, mode).apply()
            _overlayMode.value = mode
        }
    }

    fun setOverlayPet(pet: String) {
        synchronized(writeLock) {
            prefs.edit().putString(KEY_OVERLAY_PET, pet).apply()
            _overlayPet.value = pet
        }
    }

    internal fun previewOverlayAppearance(isPet: Boolean, appearance: OverlayAppearance) {
        if (isPet) {
            _petAppearance.value = appearance
        } else {
            _bubbleAppearance.value = appearance
        }
    }

    internal fun saveOverlayAppearance(isPet: Boolean) {
        synchronized(writeLock) {
            val appearance = if (isPet) _petAppearance.value else _bubbleAppearance.value
            val prefix = if (isPet) KEY_PET_APPEARANCE_PREFIX else KEY_BUBBLE_APPEARANCE_PREFIX
            prefs.edit()
                .putFloat(prefix + KEY_APPEARANCE_SCALE_SUFFIX, appearance.scale)
                .putFloat(prefix + KEY_APPEARANCE_MENU_SCALE_SUFFIX, appearance.menuScale)
                .putFloat(prefix + KEY_APPEARANCE_OPACITY_SUFFIX, appearance.opacity)
                .putBoolean(prefix + KEY_APPEARANCE_MOTION_SUFFIX, appearance.motion)
                .putBoolean(prefix + KEY_APPEARANCE_DOCKED_MOTION_SUFFIX, appearance.dockedMotion)
                .putFloat(prefix + KEY_APPEARANCE_PHYSICS_INTENSITY_SUFFIX, appearance.physicsIntensity)
                .putFloat(prefix + KEY_APPEARANCE_PHYSICS_RESPONSIVENESS_SUFFIX, appearance.physicsResponsiveness)
                .putBoolean(prefix + KEY_APPEARANCE_TAP_HAPTIC_SUFFIX, appearance.tapHaptic)
                .putFloat(prefix + KEY_APPEARANCE_TAP_HAPTIC_STRENGTH_SUFFIX, appearance.tapHapticStrength)
                .apply()
        }
    }

    private fun readOverlayMode(): String =
        prefs.getString(KEY_OVERLAY_MODE, null) ?: OVERLAY_MODE_OFF

    private fun readOverlayPet(): String =
        prefs.getString(KEY_OVERLAY_PET, null) ?: OVERLAY_PET_DEFAULT

    private fun readOverlayAppearance(isPet: Boolean): OverlayAppearance {
        val prefix = if (isPet) KEY_PET_APPEARANCE_PREFIX else KEY_BUBBLE_APPEARANCE_PREFIX
        val defaults = OverlayAppearance()
        return OverlayAppearance(
            scale = readOverlayFloat(
                key = prefix + KEY_APPEARANCE_SCALE_SUFFIX,
                default = defaults.scale,
                range = OverlayAppearance.MIN_SCALE..OverlayAppearance.MAX_SCALE
            ),
            menuScale = readOverlayFloat(
                key = prefix + KEY_APPEARANCE_MENU_SCALE_SUFFIX,
                default = defaults.menuScale,
                range = OverlayAppearance.MIN_MENU_SCALE..OverlayAppearance.MAX_MENU_SCALE
            ),
            opacity = readOverlayFloat(
                key = prefix + KEY_APPEARANCE_OPACITY_SUFFIX,
                default = defaults.opacity,
                range = OverlayAppearance.MIN_OPACITY..1f
            ),
            motion = runCatching {
                prefs.getBoolean(prefix + KEY_APPEARANCE_MOTION_SUFFIX, defaults.motion)
            }.getOrDefault(defaults.motion),
            dockedMotion = runCatching {
                prefs.getBoolean(prefix + KEY_APPEARANCE_DOCKED_MOTION_SUFFIX, defaults.dockedMotion)
            }.getOrDefault(defaults.dockedMotion),
            physicsIntensity = readOverlayFloat(
                key = prefix + KEY_APPEARANCE_PHYSICS_INTENSITY_SUFFIX,
                default = defaults.physicsIntensity,
                range = OverlayAppearance.MIN_PHYSICS_INTENSITY..OverlayAppearance.MAX_PHYSICS_INTENSITY
            ),
            physicsResponsiveness = readOverlayFloat(
                key = prefix + KEY_APPEARANCE_PHYSICS_RESPONSIVENESS_SUFFIX,
                default = defaults.physicsResponsiveness,
                range = OverlayAppearance.MIN_PHYSICS_RESPONSIVENESS..OverlayAppearance.MAX_PHYSICS_RESPONSIVENESS
            ),
            tapHaptic = runCatching {
                prefs.getBoolean(prefix + KEY_APPEARANCE_TAP_HAPTIC_SUFFIX, defaults.tapHaptic)
            }.getOrDefault(defaults.tapHaptic),
            tapHapticStrength = readOverlayFloat(
                key = prefix + KEY_APPEARANCE_TAP_HAPTIC_STRENGTH_SUFFIX,
                default = defaults.tapHapticStrength,
                range = OverlayAppearance.MIN_TAP_HAPTIC_STRENGTH..OverlayAppearance.MAX_TAP_HAPTIC_STRENGTH
            )
        )
    }

    private fun readOverlayFloat(
        key: String,
        default: Float,
        range: ClosedFloatingPointRange<Float>
    ): Float = runCatching { prefs.getFloat(key, default) }
        .getOrDefault(default)
        .takeIf { it.isFinite() }
        ?.coerceIn(range)
        ?: default

    private fun readThemePreference(): ThemePreference {
        val raw = prefs.getString(KEY_THEME_PREFERENCE, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(raw) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    private fun readQuickNotificationEnabled(): Boolean =
        prefs.getBoolean(KEY_QUICK_NOTIFICATION_ENABLED, true)

    private fun readQuickNotificationExpanded(): Boolean =
        prefs.getBoolean(KEY_QUICK_NOTIFICATION_EXPANDED, true)

    companion object {
        private const val PREFS_NAME = "moment_ledger_prefs"
        private const val KEY_THEME_PREFERENCE = "theme_preference"
        private const val KEY_QUICK_NOTIFICATION_ENABLED = "quick_notification_enabled"
        private const val KEY_QUICK_NOTIFICATION_EXPANDED = "quick_notification_expanded"
        private const val KEY_SCREEN_CAPTURE_ENABLED = "screen_capture_enabled"
        private const val KEY_EXCLUDE_FROM_RECENTS = "exclude_from_recents"
        private const val KEY_OVERLAY_MODE = "quick_overlay_mode"
        private const val KEY_OVERLAY_PET = "quick_overlay_pet"
        private const val KEY_BUBBLE_APPEARANCE_PREFIX = "overlay_bubble_appearance_"
        private const val KEY_PET_APPEARANCE_PREFIX = "overlay_pet_appearance_"
        private const val KEY_APPEARANCE_SCALE_SUFFIX = "scale"
        private const val KEY_APPEARANCE_MENU_SCALE_SUFFIX = "menu_scale"
        private const val KEY_APPEARANCE_OPACITY_SUFFIX = "opacity"
        private const val KEY_APPEARANCE_MOTION_SUFFIX = "motion"
        private const val KEY_APPEARANCE_DOCKED_MOTION_SUFFIX = "docked_motion"
        private const val KEY_APPEARANCE_PHYSICS_INTENSITY_SUFFIX = "physics_intensity"
        private const val KEY_APPEARANCE_PHYSICS_RESPONSIVENESS_SUFFIX = "physics_responsiveness"
        private const val KEY_APPEARANCE_TAP_HAPTIC_SUFFIX = "tap_haptic"
        private const val KEY_APPEARANCE_TAP_HAPTIC_STRENGTH_SUFFIX = "tap_haptic_strength"
        private const val OVERLAY_MODE_OFF = "off"
        private const val OVERLAY_PET_DEFAULT = "hitori"
        private const val KEY_TIME_PERIODS = "time_periods"
        private const val KEY_BUDGET = "budget_v2"
        private const val DAILY_BUDGET_RETENTION_DAYS = 400L
        private val prefJson = Json { ignoreUnknownKeys = true }
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

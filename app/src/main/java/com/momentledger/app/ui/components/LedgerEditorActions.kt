package com.momentledger.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.momentledger.app.data.prefs.LedgerPreferences

internal data class LedgerEditorActions(
    val preferences: LedgerPreferences,
    val archiveCategory: suspend (String) -> Boolean,
    val restoreDefaultCategories: suspend (List<String>) -> Unit
)

internal val LocalLedgerEditorActions = staticCompositionLocalOf<LedgerEditorActions?> { null }

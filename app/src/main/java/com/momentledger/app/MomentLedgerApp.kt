package com.momentledger.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.momentledger.app.data.db.LedgerDatabase
import com.momentledger.app.data.prefs.LedgerPreferences
import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.data.repository.RoomLedgerStore
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.ImportReviewBatch
import com.momentledger.app.ocr.ScreenshotOcrEngine
import com.momentledger.app.pdf.PdfStatementExtractor
import com.momentledger.app.quickentry.AccessibilityAutoRestore
import com.momentledger.app.quickentry.QuickEntryNotificationHelper
import com.momentledger.app.quickentry.ScreenReviewHandoff
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId

class MomentLedgerApp : Application() {

    private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("MomentLedgerApp", "Uncaught background coroutine error", throwable)
    }

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + appExceptionHandler)
    internal val screenReviews = ScreenReviewHandoff<ImportReviewBatch>(SystemClock::elapsedRealtime)

    val database: LedgerDatabase by lazy {
        LedgerDatabase.getInstance(this)
    }

    val preferences: LedgerPreferences by lazy {
        LedgerPreferences(this)
    }

    val deduplicationEngine: DeduplicationEngine by lazy {
        DeduplicationEngine(zoneId = ZoneId.systemDefault())
    }

    val repository: LedgerRepository by lazy {
        LedgerRepository(
            store = RoomLedgerStore(database),
            dedupEngine = deduplicationEngine,
            zoneId = ZoneId.systemDefault()
        )
    }

    val screenshotOcrEngine: ScreenshotOcrEngine by lazy {
        ScreenshotOcrEngine(this)
    }

    val pdfStatementExtractor: PdfStatementExtractor by lazy {
        PdfStatementExtractor(this, screenshotOcrEngine)
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch(Dispatchers.IO) {
            runCatching {
                QuickEntryNotificationHelper.syncNotification(
                    context = this@MomentLedgerApp,
                    enabled = preferences.quickNotificationEnabled.value
                )
            }
            runCatching {
                AccessibilityAutoRestore.tryRestoreIfNeeded(this@MomentLedgerApp)
            }
            runCatching {
                repository.ensureSeeded()
            }
        }
    }

    companion object {
        fun from(context: Context): MomentLedgerApp =
            context.applicationContext as MomentLedgerApp
    }
}

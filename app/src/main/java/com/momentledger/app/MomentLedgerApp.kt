package com.momentledger.app

import android.app.Application
import android.content.Context
import com.momentledger.app.data.db.LedgerDatabase
import com.momentledger.app.data.prefs.LedgerPreferences
import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.data.repository.RoomLedgerStore
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.ocr.ScreenshotOcrEngine
import com.momentledger.app.pdf.PdfStatementExtractor
import com.momentledger.app.quickentry.QuickEntryNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId

class MomentLedgerApp : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            repository.ensureSeeded()
        }
        QuickEntryNotificationHelper.syncNotification(
            context = this,
            enabled = preferences.quickNotificationEnabled.value
        )
    }

    companion object {
        fun from(context: Context): MomentLedgerApp =
            context.applicationContext as MomentLedgerApp
    }
}

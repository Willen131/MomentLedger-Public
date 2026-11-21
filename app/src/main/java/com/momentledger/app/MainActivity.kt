package com.momentledger.app

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.momentledger.app.data.prefs.ThemePreference
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.parser.BankPdfParser
import com.momentledger.app.domain.parser.BillFileParser
import com.momentledger.app.domain.parser.ScreenshotBillParser
import com.momentledger.app.pdf.PdfStatementExtractor
import com.momentledger.app.quickentry.QuickEntryActions
import com.momentledger.app.quickentry.QuickEntryNotificationHelper
import com.momentledger.app.ui.calendar.CalendarScreen
import com.momentledger.app.ui.calendar.CalendarViewModel
import com.momentledger.app.ui.components.AppTab
import com.momentledger.app.ui.components.AppleBottomBar
import com.momentledger.app.ui.components.TransactionEditorSheet
import com.momentledger.app.ui.components.LedgerEditorActions
import com.momentledger.app.ui.components.LocalLedgerEditorActions
import com.momentledger.app.ui.reports.ReportsScreen
import com.momentledger.app.domain.engine.BudgetAlert
import com.momentledger.app.domain.engine.BudgetPeriod
import com.momentledger.app.domain.engine.budgetCycle
import com.momentledger.app.domain.engine.calculateBudget
import kotlinx.coroutines.flow.flowOf
import com.momentledger.app.ui.review.ImportReviewScreen
import com.momentledger.app.ui.review.ImportReviewViewModel
import com.momentledger.app.ui.settings.CategoryManagerScreen
import com.momentledger.app.ui.settings.SettingsScreen
import com.momentledger.app.ui.settings.SettingsViewModel
import com.momentledger.app.ui.stats.StatsScreen
import com.momentledger.app.ui.stats.StatsViewModel
import com.momentledger.app.ui.theme.AppleTheme
import com.momentledger.app.ui.theme.LocalAppleColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.io.ByteArrayInputStream
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private val pendingEditTransactionId = mutableStateOf<Long?>(null)
    private val pendingQuickAction = mutableStateOf<String?>(null)
    private val pendingSharedImageUris = mutableStateOf<List<Uri>>(emptyList())
    private val pendingSharedPdfUri = mutableStateOf<Uri?>(null)
    private val permissionRefreshTrigger = mutableIntStateOf(0)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefreshTrigger.intValue += 1
        if (granted && MomentLedgerApp.from(this).preferences.quickNotificationEnabled.value) {
            QuickEntryNotificationHelper.syncNotification(this, enabled = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        val app = MomentLedgerApp.from(this)
        QuickEntryNotificationHelper.syncNotification(this, app.preferences.quickNotificationEnabled.value)
        if (pendingQuickAction.value == null &&
            pendingSharedImageUris.value.isEmpty() &&
            pendingSharedPdfUri.value == null &&
            pendingEditTransactionId.value == null
        ) {
            requestNotificationPermissionOnceIfNeeded()
        }

        setContent {
            val themePref by app.preferences.themePreference.collectAsStateWithLifecycle()
            val darkTheme = when (themePref) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            DisposableEffect(darkTheme) {
                val statusBarStyle = SystemBarStyle.auto(
                    lightScrim = android.graphics.Color.TRANSPARENT,
                    darkScrim = android.graphics.Color.TRANSPARENT
                ) { darkTheme }
                val navigationBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
                    )
                }
                enableEdgeToEdge(
                    statusBarStyle = statusBarStyle,
                    navigationBarStyle = navigationBarStyle
                )
                onDispose {}
            }

            AppleTheme(darkTheme = darkTheme) {
                val editorActions = remember(app.repository, app.preferences) {
                    LedgerEditorActions(
                        app.preferences, app.repository::archiveCategory, app.repository::restoreDefaultCategories
                    )
                }
                CompositionLocalProvider(LocalLedgerEditorActions provides editorActions) {
                    MomentLedgerRoot(
                        app = app,
                        pendingEditTransactionId = pendingEditTransactionId.value,
                        pendingQuickAction = pendingQuickAction.value,
                        pendingSharedImageUris = pendingSharedImageUris.value,
                        pendingSharedPdfUri = pendingSharedPdfUri.value,
                        permissionRefreshTick = permissionRefreshTrigger.intValue,
                        onConsumePendingEditId = { consumedId ->
                            if (pendingEditTransactionId.value == consumedId) {
                                pendingEditTransactionId.value = null
                            }
                        },
                        onConsumeQuickAction = { consumed ->
                            if (pendingQuickAction.value == consumed) {
                                pendingQuickAction.value = null
                            }
                        },
                        onConsumeSharedImages = {
                            pendingSharedImageUris.value = emptyList()
                        },
                        onConsumeSharedPdf = {
                            pendingSharedPdfUri.value = null
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = MomentLedgerApp.from(this)
        QuickEntryNotificationHelper.syncNotification(this, app.preferences.quickNotificationEnabled.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val txId = intent.getLongExtra(EXTRA_EDIT_TRANSACTION_ID, -1L)
        if (txId > 0L) {
            pendingEditTransactionId.value = txId
            intent.removeExtra(EXTRA_EDIT_TRANSACTION_ID)
        }

        val quickAction = intent.getStringExtra(QuickEntryActions.EXTRA_QUICK_ACTION)
            ?: intent.action?.takeIf {
                it == QuickEntryActions.ACTION_QUICK_ADD ||
                    it == QuickEntryActions.ACTION_SCREENSHOT_OCR ||
                    it == QuickEntryActions.ACTION_IMPORT_BILL
            }
        if (quickAction != null) {
            pendingQuickAction.value = quickAction
            intent.removeExtra(QuickEntryActions.EXTRA_QUICK_ACTION)
            if (intent.action == QuickEntryActions.ACTION_QUICK_ADD ||
                intent.action == QuickEntryActions.ACTION_SCREENSHOT_OCR ||
                intent.action == QuickEntryActions.ACTION_IMPORT_BILL
            ) {
                intent.action = null
            }
        }

        val sharedPdfUri = extractSharedPdfUri(intent)
        if (sharedPdfUri != null) {
            pendingSharedPdfUri.value = sharedPdfUri
            intent.action = null
            intent.removeExtra(Intent.EXTRA_STREAM)
            return
        }

        val sharedUris = extractSharedImageUris(intent)
        if (sharedUris.isNotEmpty()) {
            pendingSharedImageUris.value = sharedUris
            intent.action = null
            intent.removeExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractSharedPdfUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        val mime = intent.type?.lowercase().orEmpty()
        val candidate = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: return null
        if (candidate.scheme != ContentResolver.SCHEME_CONTENT) return null
        val resolvedType = runCatching { contentResolver.getType(candidate) }.getOrNull()
        val isPdfMime = mime == "application/pdf" ||
            resolvedType?.equals("application/pdf", ignoreCase = true) == true
        val isPdfName = resolveDisplayName(this, candidate)?.endsWith(".pdf", ignoreCase = true) == true
        return if (isPdfMime || isPdfName) candidate else null
    }

    private fun extractSharedImageUris(intent: Intent): List<Uri> {
        val action = intent.action ?: return emptyList()
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return emptyList()
        }
        val uris = LinkedHashSet<Uri>()
        fun addSafeUri(uri: Uri?) {
            if (uri != null && uri.scheme == ContentResolver.SCHEME_CONTENT && uris.size < MAX_SHARED_IMAGES) {
                uris += uri
            }
        }
        if (action == Intent.ACTION_SEND) {
            addSafeUri(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.forEach { addSafeUri(it) }
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                addSafeUri(clip.getItemAt(i)?.uri)
            }
        }
        return uris.toList()
    }

    private fun requestNotificationPermissionOnceIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        val uiPrefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        if (!uiPrefs.getBoolean(KEY_ASKED_POST_NOTIFICATIONS, false)) {
            uiPrefs.edit().putBoolean(KEY_ASKED_POST_NOTIFICATIONS, true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_EDIT_TRANSACTION_ID = "extra_edit_transaction_id"
        private const val UI_PREFS_NAME = "moment_ledger_ui_prefs"
        private const val KEY_ASKED_POST_NOTIFICATIONS = "asked_post_notifications"
        private const val MAX_SHARED_IMAGES = 20
    }
}

@Composable
private fun MomentLedgerRoot(
    app: MomentLedgerApp,
    pendingEditTransactionId: Long?,
    pendingQuickAction: String?,
    pendingSharedImageUris: List<Uri>,
    pendingSharedPdfUri: Uri?,
    permissionRefreshTick: Int,
    onConsumePendingEditId: (Long) -> Unit,
    onConsumeQuickAction: (String) -> Unit,
    onConsumeSharedImages: () -> Unit,
    onConsumeSharedPdf: () -> Unit
) {
    val colors = LocalAppleColors.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val saveEditorCategory: suspend (Category) -> Unit = { category ->
        withContext(Dispatchers.IO) { app.repository.saveCustomCategory(category) }
    }
    val calendarViewModel: CalendarViewModel = viewModel(
        factory = remember(app.repository) { CalendarViewModel.factory(app.repository) }
    )
    val statsViewModel: StatsViewModel = viewModel(
        factory = remember(app.repository) { StatsViewModel.factory(app.repository, preferences = app.preferences) }
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = remember(app.repository, app.preferences) {
            SettingsViewModel.factory(app.repository, app.preferences)
        }
    )
    val reviewViewModel: ImportReviewViewModel = viewModel(
        factory = remember(app.repository) { ImportReviewViewModel.factory(app.repository) }
    )

    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val reviewUiState by reviewViewModel.uiState.collectAsStateWithLifecycle()
    val allCategories = remember(settingsUiState.expenseCategories, settingsUiState.incomeCategories) {
        settingsUiState.expenseCategories + settingsUiState.incomeCategories
    }

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.CALENDAR) }
    val budget by app.preferences.budget.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val activeBudgetPeriods = remember(budget, today) {
        BudgetPeriod.entries.filter { budget.amountFor(it, today) != null }
    }
    val loadReportRecords = selectedTab == AppTab.REPORTS || activeBudgetPeriods.isNotEmpty()
    val reportRecordsFlow = remember(app.repository, loadReportRecords) {
        if (loadReportRecords) app.repository.observeTransactionsInDateRange("0001-01-01", "9999-12-31")
        else flowOf(emptyList<TransactionRecord>())
    }
    val reportRecords by reportRecordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val budgetAlerts = remember(reportRecords, budget, today, activeBudgetPeriods) {
        activeBudgetPeriods.mapNotNull { period ->
            val cycle = budgetCycle(today, period, budget)
            calculateBudget(reportRecords, cycle, budget.amountFor(period, today), today)
                ?.takeIf { it.alert != BudgetAlert.NORMAL }
                ?.let { Triple(period, cycle.start, it) }
        }
    }
    val notifiedBudgets = remember { mutableSetOf<Triple<BudgetPeriod, LocalDate, Pair<Long, BudgetAlert>>>() }
    LaunchedEffect(budgetAlerts) {
        val fresh = budgetAlerts.filter { (period, start, item) ->
            notifiedBudgets.add(Triple(period, start, item.limitCents to item.alert))
        }
        if (fresh.isNotEmpty()) {
            val exceeded = fresh.filter { it.third.alert == BudgetAlert.OVER }
            val message = if (exceeded.isNotEmpty()) {
                "${exceeded.size} 项预算已超支，前往「收支预算」查看"
            } else {
                "${fresh.size} 项预算接近上限，前往「收支预算」查看"
            }
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    var categoryManagerOpen by rememberSaveable { mutableStateOf(false) }
    var reviewScreenOpen by rememberSaveable { mutableStateOf(false) }
    var quickAddOpen by remember { mutableStateOf(false) }
    var deepLinkEditingRecord by remember { mutableStateOf<TransactionRecord?>(null) }
    var pendingPasswordPdfUriStr by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfPasswordInput by remember { mutableStateOf("") }

    BackHandler(enabled = reviewScreenOpen) {
        reviewScreenOpen = false
        pendingPasswordPdfUriStr = null
        pdfPasswordInput = ""
        reviewViewModel.reset()
    }

    BackHandler(enabled = categoryManagerOpen && !reviewScreenOpen) {
        categoryManagerOpen = false
    }

    fun startScreenshotOcrReview(uris: List<Uri>) {
        if (uris.isEmpty()) return
        reviewScreenOpen = true
        categoryManagerOpen = false
        quickAddOpen = false
        val safeUris = uris.take(20)
        reviewViewModel.startLoading(
            title = "正在离线识别 ${safeUris.size} 张账单截图...",
            errorPrefix = "截图识别失败"
        ) {
            val zoneId = ZoneId.systemDefault()
            val allPayments = mutableListOf<com.momentledger.app.domain.model.ParsedPayment>()
            val verificationFlags = mutableListOf<Boolean>()
            val rawTexts = mutableListOf<String>()

            for ((idx, uri) in safeUris.withIndex()) {
                val ocrInput = app.screenshotOcrEngine.recognizeUri(uri)
                val parsed = ScreenshotBillParser.parse(ocrInput, zoneId)
                if (parsed.rawText.isNotBlank()) {
                    val header = if (safeUris.size > 1) "【截图 ${idx + 1}】\n" else ""
                    rawTexts += "$header${parsed.rawText}"
                }
                for (candidate in parsed.candidates) {
                    allPayments += candidate.payment
                    verificationFlags += candidate.needsVerification
                }
            }

            app.repository.prepareImportReview(
                items = allPayments,
                sourceTitle = if (safeUris.size == 1) "截图识账" else "截图识账 (${safeUris.size}张)",
                skippedCount = 0,
                rawOcrText = rawTexts.joinToString("\n\n"),
                imageUris = safeUris.map { it.toString() },
                needsVerificationFlags = verificationFlags,
                dedupIntraBatch = true
            )
        }
    }

    fun startBillFileReview(uri: Uri, pdfPassword: String = "") {
        reviewScreenOpen = true
        categoryManagerOpen = false
        quickAddOpen = false
        pendingPasswordPdfUriStr = uri.toString()
        val appContext = app.applicationContext
        reviewViewModel.startLoading(
            title = if (pdfPassword.isNotEmpty()) "正在解锁并解析银行 PDF 账单..." else "正在离线解析账单文件并匹配分类...",
            errorPrefix = "解析账单失败"
        ) {
            val zoneId = ZoneId.systemDefault()
            val fileNameHint = resolveDisplayName(appContext, uri) ?: "bill.csv"
            val mimeHint = runCatching { appContext.contentResolver.getType(uri) }.getOrNull().orEmpty()
            val likelyPdf = fileNameHint.endsWith(".pdf", ignoreCase = true) ||
                mimeHint.equals("application/pdf", ignoreCase = true)

            if (likelyPdf) {
                val extracted = app.pdfStatementExtractor.extractPages(uri, password = pdfPassword)
                val parsed = BankPdfParser.parsePages(
                    pages = extracted.pages,
                    fileName = fileNameHint,
                    zoneId = zoneId
                )
                val sourceTitle = if (extracted.truncated) {
                    "银行 PDF 流水整理（前 ${extracted.pages.size}/${extracted.totalPageCount} 页）"
                } else {
                    "银行 PDF 流水整理"
                }
                app.repository.prepareImportReview(
                    items = parsed.items,
                    sourceTitle = sourceTitle,
                    skippedCount = parsed.skippedCount,
                    dedupIntraBatch = false
                )
            } else {
                val rawBytes = app.pdfStatementExtractor.readUriBytesBounded(uri)
                if (rawBytes.isEmpty()) error("无法读取所选账单文件")
                if (PdfStatementExtractor.hasPdfMagicHeader(rawBytes)) {
                    val extracted = app.pdfStatementExtractor.extractPagesFromBytes(rawBytes, password = pdfPassword)
                    val parsed = BankPdfParser.parsePages(
                        pages = extracted.pages,
                        fileName = fileNameHint,
                        zoneId = zoneId
                    )
                    val sourceTitle = if (extracted.truncated) {
                        "银行 PDF 流水整理（前 ${extracted.pages.size}/${extracted.totalPageCount} 页）"
                    } else {
                        "银行 PDF 流水整理"
                    }
                    app.repository.prepareImportReview(
                        items = parsed.items,
                        sourceTitle = sourceTitle,
                        skippedCount = parsed.skippedCount,
                        dedupIntraBatch = false
                    )
                } else {
                    if (rawBytes.size > 20 * 1024 * 1024) {
                        error("账单文件过大（上限 20MB）")
                    }
                    val parsed = BillFileParser.parse(
                        fileName = fileNameHint,
                        inputStream = ByteArrayInputStream(rawBytes),
                        zoneId = zoneId
                    )
                    val title = when (parsed.detectedFormat) {
                        "WECHAT_XLSX", "WECHAT_CSV" -> "微信账单整理"
                        "ALIPAY_CSV", "ALIPAY_XLSX" -> "支付宝账单整理"
                        "GENERIC_CSV", "GENERIC_XLSX" -> "软件账单整理"
                        else -> "账单导入整理"
                    }
                    app.repository.prepareImportReview(
                        items = parsed.items,
                        sourceTitle = title,
                        skippedCount = parsed.skippedCount,
                        dedupIntraBatch = false
                    )
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            startScreenshotOcrReview(uris)
        }
    }

    val legacyImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            startScreenshotOcrReview(uris)
        }
    }

    fun launchScreenshotPicker() {
        val launched = runCatching {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.isSuccess
        if (!launched) {
            runCatching { legacyImagePickerLauncher.launch(arrayOf("image/*")) }
        }
    }

    val billFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            startBillFileReview(uri)
        }
    }

    fun launchBillFilePicker() {
        billFilePickerLauncher.launch(
            arrayOf(
                "application/pdf",
                "text/*",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    LaunchedEffect(permissionRefreshTick) {
        if (permissionRefreshTick > 0) {
            settingsViewModel.refreshNotificationPermission(
                QuickEntryNotificationHelper.hasNotificationPermission(context)
            )
        }
    }

    LaunchedEffect(pendingEditTransactionId) {
        val txId = pendingEditTransactionId ?: return@LaunchedEffect
        if (txId > 0L) {
            val loaded = withContext(Dispatchers.IO) {
                app.repository.getTransactionById(txId)
            }
            if (loaded != null) {
                deepLinkEditingRecord = loaded
            }
        }
        onConsumePendingEditId(txId)
    }

    LaunchedEffect(pendingQuickAction) {
        val action = pendingQuickAction ?: return@LaunchedEffect
        onConsumeQuickAction(action)
        when (action) {
            QuickEntryActions.ACTION_QUICK_ADD -> {
                reviewScreenOpen = false
                categoryManagerOpen = false
                quickAddOpen = true
            }
            QuickEntryActions.ACTION_SCREENSHOT_OCR -> {
                launchScreenshotPicker()
            }
            QuickEntryActions.ACTION_IMPORT_BILL -> {
                launchBillFilePicker()
            }
        }
    }

    LaunchedEffect(pendingSharedImageUris) {
        if (pendingSharedImageUris.isNotEmpty()) {
            val copy = pendingSharedImageUris.toList()
            onConsumeSharedImages()
            startScreenshotOcrReview(copy)
        }
    }

    LaunchedEffect(pendingSharedPdfUri) {
        val pdfUri = pendingSharedPdfUri ?: return@LaunchedEffect
        onConsumeSharedPdf()
        startBillFileReview(pdfUri)
    }

    LaunchedEffect(settingsUiState.statusMessage) {
        val message = settingsUiState.statusMessage ?: return@LaunchedEffect
        try {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        } finally {
            if (settingsViewModel.uiState.value.statusMessage == message) {
                settingsViewModel.clearStatusMessage()
            }
        }
    }

    val hideBottomBar = categoryManagerOpen || reviewScreenOpen

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(if (hideBottomBar) Modifier.navigationBarsPadding() else Modifier)
            ) { data ->
                Snackbar(
                    shape = RoundedCornerShape(14.dp),
                    containerColor = colors.card,
                    contentColor = colors.text
                ) {
                    Text(
                        text = data.visuals.message,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        bottomBar = {
            if (!hideBottomBar) {
                AppleBottomBar(
                    selectedTab = selectedTab,
                    onSelectTab = { nextTab ->
                        selectedTab = nextTab
                        categoryManagerOpen = false
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                reviewScreenOpen -> {
                    ImportReviewScreen(
                        viewModel = reviewViewModel,
                        onCancel = {
                            reviewScreenOpen = false
                            pendingPasswordPdfUriStr = null
                            pdfPasswordInput = ""
                            reviewViewModel.reset()
                        },
                        onCompleted = { summary ->
                            reviewScreenOpen = false
                            pendingPasswordPdfUriStr = null
                            pdfPasswordInput = ""
                            selectedTab = AppTab.CALENDAR
                            val msg = buildString {
                                append("已导入 ${summary.insertedCount} 笔")
                                if (summary.mergedCount > 0) append(" · 合并更新 ${summary.mergedCount} 笔")
                                if (summary.skippedCount > 0) append(" · 跳过 ${summary.skippedCount} 笔")
                            }
                            settingsViewModel.postStatusMessage(msg)
                        }
                    )
                }
                categoryManagerOpen -> {
                    CategoryManagerScreen(
                        expenseCategories = settingsUiState.expenseCategories,
                        incomeCategories = settingsUiState.incomeCategories,
                        onAddOrUpdateCategory = settingsViewModel::addOrUpdateCategory,
                        onDeleteCategory = settingsViewModel::deleteCategory,
                        onMoveCategory = settingsViewModel::moveCategory,
                        onBack = { categoryManagerOpen = false }
                    )
                }
                else -> {
                    when (selectedTab) {
                        AppTab.CALENDAR -> CalendarScreen(
                            viewModel = calendarViewModel,
                            onSaveCategory = saveEditorCategory
                        )
                        AppTab.STATS -> StatsScreen(viewModel = statsViewModel, onSaveCategory = saveEditorCategory)
                        AppTab.REPORTS -> ReportsScreen(
                            records = reportRecords, categories = allCategories, today = today, budget = budget,
                            onBudgetChange = app.preferences::setBudget
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            viewModel = settingsViewModel,
                            onOpenCategoryManager = { categoryManagerOpen = true },
                            onQuickAdd = { quickAddOpen = true },
                            onPickScreenshots = ::launchScreenshotPicker,
                            onPickBillFile = ::launchBillFilePicker,
                            showStatusSnackbar = false
                        )
                    }
                }
            }
        }
    }

    if (quickAddOpen) {
        TransactionEditorSheet(
            initial = null,
            categories = allCategories,
            onSaveCategory = saveEditorCategory,
            autoFocusAmount = true,
            onSwitchToScreenshotOcr = {
                quickAddOpen = false
                launchScreenshotPicker()
            },
            onSave = { created ->
                quickAddOpen = false
                app.appScope.launch(Dispatchers.IO) {
                    app.repository.saveManualTransaction(created)
                }
                settingsViewModel.postStatusMessage("已记录一笔账单")
            },
            onDelete = null,
            onDismiss = { quickAddOpen = false }
        )
    }

    reviewUiState.pdfPasswordPromptMessage?.let { promptMessage ->
        val targetPdf = pendingPasswordPdfUriStr?.let(Uri::parse)
        if (targetPdf != null) {
            AlertDialog(
                onDismissRequest = {
                    pdfPasswordInput = ""
                    pendingPasswordPdfUriStr = null
                    reviewViewModel.clearPdfPasswordPrompt()
                    reviewScreenOpen = false
                    reviewViewModel.reset()
                },
                containerColor = colors.card,
                titleContentColor = colors.text,
                textContentColor = colors.subText,
                title = {
                    Text(
                        text = "输入 PDF 账单密码",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            text = promptMessage,
                            color = colors.subText,
                            fontSize = 13.sp
                        )
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        OutlinedTextField(
                            value = pdfPasswordInput,
                            onValueChange = { pdfPasswordInput = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false
                            ),
                            placeholder = { Text("通常为银行发来的 6 位查询密码") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = pdfPasswordInput.isNotBlank(),
                        onClick = {
                            val pw = pdfPasswordInput.trim()
                            pdfPasswordInput = ""
                            reviewViewModel.clearPdfPasswordPrompt()
                            startBillFileReview(targetPdf, pdfPassword = pw)
                        }
                    ) {
                        Text(
                            "解锁并解析",
                            color = if (pdfPasswordInput.isNotBlank()) colors.blue else colors.subText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            pdfPasswordInput = ""
                            pendingPasswordPdfUriStr = null
                            reviewViewModel.clearPdfPasswordPrompt()
                            reviewScreenOpen = false
                            reviewViewModel.reset()
                        }
                    ) {
                        Text("取消", color = colors.subText)
                    }
                }
            )
        }
    }

    deepLinkEditingRecord?.let { editing ->
        TransactionEditorSheet(
            initial = editing,
            categories = allCategories,
            onSaveCategory = saveEditorCategory,
            onSave = { updated ->
                deepLinkEditingRecord = null
                app.appScope.launch(Dispatchers.IO) {
                    app.repository.updateTransaction(updated, learnMerchantCategory = true)
                }
            },
            onDelete = { txId ->
                deepLinkEditingRecord = null
                app.appScope.launch(Dispatchers.IO) {
                    app.repository.deleteTransaction(txId)
                }
            },
            onDismiss = { deepLinkEditingRecord = null }
        )
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val value = cursor.getString(idx)?.trim()
                    if (!value.isNullOrEmpty()) return value
                }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

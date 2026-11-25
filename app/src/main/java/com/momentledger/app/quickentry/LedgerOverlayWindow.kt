package com.momentledger.app.quickentry

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.momentledger.app.data.prefs.OverlayAppearance
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class OverlayMode(val id: String, val label: String) {
    OFF("off", "关闭"),
    BUBBLE("bubble", "快捷悬浮球");

    companion object {
        fun fromId(id: String): OverlayMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

internal class LedgerOverlayWindow(
    private val context: Context,
    private val onAdd: () -> Unit,
    private val onCapture: () -> Unit
) {
    private val wm = requireNotNull(context.getSystemService(WindowManager::class.java))
    private val params = WindowManager.LayoutParams(
        0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        title = "MomentLedger floating quick entry"
    }

    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && menuOpen && !previewMenuPinned) {
                closeMenu()
                return false
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private val handle = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(0xFFFAFCFF.toInt(), 18, 0xFFD5E4FA.toInt())
    }

    private val actionButtons = mutableListOf<TextView>()
    private val handleLp = FrameLayout.LayoutParams(0, 0)
    private val menuLp = FrameLayout.LayoutParams(0, 0)
    private var mode = OverlayMode.OFF
    private var appearance = OverlayAppearance()
    private val menuScale get() = (appearance.scale.coerceIn(0.68f, 1.45f) * appearance.menuScale).coerceIn(0.45f, 1.85f)
    private var anchor: FloatingPoint? = null
    private var dockedEdge: FloatingEdge? = null
    private var menuOpen = false
    private var previewMenuPinned = false
    private val folded: FloatingEdge? get() = if (menuOpen) null else dockedEdge
    private var attached = false
    private var area = FloatingArea(0, 0, 1, 1)
    private val fullSize get() = scaledDp(56).coerceAtMost(minOf(area.width, area.height))
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        root.addView(menu)
        root.addView(handle)
        addAction("＋  记一笔", onAdd)
        addAction("◎  一键识屏", onCapture)
        handle.setOnClickListener {
            menuOpen = !menuOpen
            configureHandle()
            layout()
        }
        handle.setOnTouchListener(DragGesture())
    }

    private fun applyMenuScale() {
        val s = menuScale
        val padH = menuDp(10)
        val padV = menuDp(6)
        menu.setPadding(padH, padV, padH, padV)
        for (button in actionButtons) {
            button.textSize = 15f * s
        }
        menu.background = rounded(0xFFFAFCFF.toInt(), (18f * s).roundToInt(), 0xFFD5E4FA.toInt())
    }

    fun render(newMode: OverlayMode, newAppearance: OverlayAppearance, visible: Boolean) {
        val scaleChanged = appearance.scale != newAppearance.scale
        val menuScaleChanged = scaleChanged || appearance.menuScale != newAppearance.menuScale
        appearance = newAppearance
        if (mode != newMode || handle.childCount == 0) {
            mode = newMode
            applyMenuScale()
            configureHandle()
        } else if (menuScaleChanged) {
            applyMenuScale()
        }
        handle.alpha = appearance.opacity
        if (scaleChanged) (handle.getChildAt(0) as? TextView)?.textSize = 25f * appearance.scale
        if (!visible || mode == OverlayMode.OFF) {
            root.visibility = View.GONE
            return
        }
        if (attached && !root.isAttachedToWindow) {
            attached = false
        }
        val wasGone = root.visibility != View.VISIBLE
        root.visibility = View.VISIBLE
        layout()
        if (!attached) {
            wm.addView(root, params)
            attached = true
        } else if (wasGone) {
            root.requestLayout()
            handle.invalidate()
        }
    }

    fun setPreviewMenuPinned(pinned: Boolean) {
        previewMenuPinned = pinned
        if (pinned && mode != OverlayMode.OFF) {
            if (!menuOpen) {
                menuOpen = true
                configureHandle()
            }
            applyMenuScale()
            layout()
        }
    }

    fun closeMenu() {
        previewMenuPinned = false
        if (!menuOpen) return
        menuOpen = false
        configureHandle()
        layout()
    }

    fun detach() {
        if (!attached) return
        if (root.isAttachedToWindow) {
            runCatching { wm.removeViewImmediate(root) }
        }
        attached = false
    }

    private fun configureHandle() {
        handle.contentDescription = if (folded == null) "快捷悬浮球" else "展开快捷悬浮球"
        handle.removeAllViews()
        handle.background = if (folded != null) {
            rounded(0xFF007AFF.toInt(), 20)
        } else {
            rounded(0xFF007AFF.toInt(), 0, 0xFFB3D7FF.toInt()).apply { shape = GradientDrawable.OVAL }
        }
        val glyph = when (folded) {
            FloatingEdge.LEFT -> "›"
            FloatingEdge.RIGHT -> "‹"
            null -> "＋"
        }
        val content = labelView(glyph, 25f * appearance.scale, Color.WHITE).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        handle.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    private fun addAction(label: String, action: () -> Unit) {
        val button = labelView(label, 15f * menuScale, 0xFF1C1C1E.toInt()).apply {
            setOnClickListener {
                closeMenu()
                action()
            }
        }
        actionButtons += button
        menu.addView(button, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun labelView(label: String, size: Float, color: Int) = TextView(context).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(color)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun layout() {
        area = displayArea()
        val handleWidth = if (folded == null) fullSize else scaledDp(38).coerceAtMost(area.width)
        val handleHeight = if (folded == null) fullSize else scaledDp(48).coerceAtMost(area.height)
        val desired = anchor ?: FloatingPoint(area.right - fullSize - dp(20), area.top + area.height * 2 / 5)
        val edgeX = when (dockedEdge) {
            FloatingEdge.LEFT -> area.left
            FloatingEdge.RIGHT -> area.right - handleWidth
            null -> desired.x
        }
        val pos = area.clamp(edgeX, desired.y, handleWidth, handleHeight)
        anchor = pos
        val menuWidth = if (menuOpen) menuDp(164).coerceAtMost(area.width) else handleWidth
        val menuHeight = if (menuOpen) menuDp(104) else 0
        val gap = if (menuOpen) menuDp(6) else 0
        val roomAbove = pos.y - area.top
        val roomBelow = area.bottom - pos.y - handleHeight
        val above = menuOpen && (roomAbove >= menuHeight + gap || roomAbove >= roomBelow)
        val actualMenuHeight = menuHeight.coerceAtMost(((if (above) roomAbove else roomBelow) - gap).coerceAtLeast(0))
        val menuX = area.clamp(pos.x + (handleWidth - menuWidth) / 2, pos.y, menuWidth, handleHeight).x
        params.x = minOf(pos.x, menuX)
        params.y = pos.y - if (above) actualMenuHeight + gap else 0
        params.width = maxOf(pos.x + handleWidth, menuX + menuWidth) - params.x
        params.height = actualMenuHeight + gap + handleHeight
        handleLp.width = handleWidth
        handleLp.height = handleHeight
        handleLp.leftMargin = pos.x - params.x
        handleLp.topMargin = pos.y - params.y
        handle.layoutParams = handleLp
        menu.visibility = if (menuOpen) View.VISIBLE else View.GONE
        menuLp.width = menuWidth
        menuLp.height = actualMenuHeight
        menuLp.leftMargin = menuX - params.x
        menuLp.topMargin = if (above) 0 else handleHeight + gap
        menu.layoutParams = menuLp
        if (attached && root.isAttachedToWindow) wm.updateViewLayout(root, params)
    }

    @Suppress("DEPRECATION")
    private fun displayArea(): FloatingArea {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return FloatingArea(insets.left, insets.top, bounds.width() - insets.right, bounds.height() - insets.bottom)
        }
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        return FloatingArea(0, dp(26), metrics.widthPixels, metrics.heightPixels - dp(48))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private fun scaledDp(value: Int) =
        (value * context.resources.displayMetrics.density * appearance.scale).roundToInt().coerceAtLeast(1)

    private fun menuDp(value: Int) =
        (value * context.resources.displayMetrics.density * menuScale).roundToInt().coerceAtLeast(1)

    private inner class DragGesture : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var start = FloatingPoint(0, 0)
        private var dragging = false

        private fun beginDrag() {
            val current = requireNotNull(anchor)
            start = if (dockedEdge == FloatingEdge.RIGHT) current.copy(x = area.right - fullSize) else current
            menuOpen = false
            dockedEdge = null
            configureHandle()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        beginDrag()
                    }
                    if (dragging) {
                        anchor = FloatingPoint(start.x + dx.roundToInt(), start.y + dy.roundToInt())
                        layout()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.performClick()
                    } else {
                        dockedEdge = area.edge(requireNotNull(anchor).x, fullSize, dp(34))
                        configureHandle()
                        layout()
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }
}

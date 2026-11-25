package com.momentledger.app.quickentry

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
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
    OFF("off", "关闭"), BUBBLE("bubble", "快捷悬浮球"), PET("pet", "角色桌宠");

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
        background = rounded(0xfffcf2f7.toInt(), 18, 0xffe6c5d7.toInt())
    }
    private val bubbleQuote = TextView(context).apply {
        textSize = 11.5f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4), dp(2), dp(4), dp(4))
        setTextColor(0xff7d546d.toInt())
        visibility = View.GONE
    }
    private val actionButtons = mutableListOf<TextView>()
    private val handleLp = FrameLayout.LayoutParams(0, 0)
    private val menuLp = FrameLayout.LayoutParams(0, 0)
    private var quoteIndex = 0
    private var petView: OverlayPetView? = null
    private val petFeedback = PetTapFeedback(context)
    private var mode = OverlayMode.OFF
    private var pet = OverlayPet.HITORI
    private var appearance = OverlayAppearance()
    private val menuScale get() = (appearance.scale.coerceIn(0.68f, 1.45f) * appearance.menuScale).coerceIn(0.45f, 1.85f)
    private var anchor: FloatingPoint? = null
    // Edge the overlay stays docked to; survives menu open/close until the next drag.
    private var dockedEdge: FloatingEdge? = null
    private var menuOpen = false
    // Keeps the popup menu visible while the user drags the "弹出卡片大小" slider in Settings.
    private var previewMenuPinned = false
    // While the menu is open the handle expands, so it is not visually folded.
    private val folded: FloatingEdge? get() = if (menuOpen) null else dockedEdge
    private var attached = false
    private var area = FloatingArea(0, 0, 1, 1)
    private val fullSize get() = scaledDp(if (mode == OverlayMode.PET) 86 else 56)
        .coerceAtMost(minOf(area.width, area.height))
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        root.addView(menu)
        root.addView(handle)
        menu.addView(bubbleQuote, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        addAction("＋  记一笔", onAdd)
        addAction("◎  一键识屏", onCapture)
        handle.setOnClickListener {
            menuOpen = !menuOpen
            if (menuOpen && mode == OverlayMode.PET) {
                refreshPetSpeechBubble()
            }
            configureHandle()
            layout()
            petView?.onTapJelly()
            if (mode == OverlayMode.PET) {
                petFeedback.onPetTapped(
                    anchorView = handle,
                    pet = pet,
                    hapticEnabled = appearance.tapHaptic,
                    strength = appearance.tapHapticStrength
                )
            }
        }
        handle.setOnTouchListener(DragGesture())
        handle.setOnHoverListener { v, event ->
            if (mode == OverlayMode.PET && v.width > 0 && v.height > 0) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                        val nx = ((event.x / v.width) - 0.5f) * 2f
                        val ny = ((event.y / v.height) - 0.5f) * 2f
                        petView?.onPointerLook(nx, ny)
                    }
                }
            }
            false
        }
    }

    private fun refreshPetSpeechBubble() {
        val profile = PetPhysicsProfiles.forPetId(pet.id)
        val lines = profile.speechLines
        if (lines.isNotEmpty()) {
            bubbleQuote.text = lines[quoteIndex % lines.size]
            quoteIndex = (quoteIndex + 1) % lines.size
        }
        bubbleQuote.setTextColor(profile.bubbleAccentColor)
        bubbleQuote.visibility = View.VISIBLE
        menu.background = rounded(0xfffef8fb.toInt(), (18f * menuScale).roundToInt(), profile.bubbleAccentColor)
    }

    private fun applyMenuScale() {
        val s = menuScale
        val padH = menuDp(10)
        val padV = menuDp(6)
        menu.setPadding(padH, padV, padH, padV)
        bubbleQuote.textSize = 11.5f * s
        bubbleQuote.setPadding(menuDp(4), menuDp(2), menuDp(4), menuDp(4))
        for (button in actionButtons) {
            button.textSize = 15f * s
        }
        val radiusDp = (18f * s).roundToInt()
        if (mode == OverlayMode.PET) {
            val profile = PetPhysicsProfiles.forPetId(pet.id)
            menu.background = rounded(0xfffef8fb.toInt(), radiusDp, profile.bubbleAccentColor)
        } else {
            menu.background = rounded(0xfffcf2f7.toInt(), radiusDp, 0xffe6c5d7.toInt())
        }
    }

    fun render(newMode: OverlayMode, newPet: OverlayPet, newAppearance: OverlayAppearance, visible: Boolean) {
        val scaleChanged = appearance.scale != newAppearance.scale
        val menuScaleChanged = scaleChanged || appearance.menuScale != newAppearance.menuScale
        appearance = newAppearance
        if (mode != newMode || pet != newPet || handle.childCount == 0) {
            mode = newMode
            pet = newPet
            if (mode == OverlayMode.PET) {
                if (menuOpen) refreshPetSpeechBubble()
            } else {
                bubbleQuote.visibility = View.GONE
            }
            applyMenuScale()
            configureHandle()
        } else if (menuScaleChanged) {
            applyMenuScale()
        }
        handle.alpha = appearance.opacity
        petView?.setPose(folded, appearance.motion, appearance.dockedMotion)
        petView?.setPhysicsConfig(
            intensity = appearance.physicsIntensity,
            responsiveness = appearance.physicsResponsiveness
        )
        if (scaleChanged) (handle.getChildAt(0) as? TextView)?.textSize = 25f * appearance.scale
        if (!visible || mode == OverlayMode.OFF) {
            root.visibility = View.GONE
            return
        }
        // Recover automatically if SystemUI detached the overlay window during deep sleep
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
            petView?.invalidate()
        }
    }

    fun setPreviewMenuPinned(pinned: Boolean) {
        previewMenuPinned = pinned
        if (pinned && mode != OverlayMode.OFF) {
            if (!menuOpen) {
                menuOpen = true
                if (mode == OverlayMode.PET) {
                    refreshPetSpeechBubble()
                }
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
        val label = if (mode == OverlayMode.PET) pet.label else "快捷悬浮球"
        handle.contentDescription = if (folded == null) label else "展开$label"
        val currentPet = petView
        if (mode == OverlayMode.PET && currentPet?.pet == pet) {
            currentPet.setPose(folded, appearance.motion, appearance.dockedMotion)
            currentPet.setPhysicsConfig(
                intensity = appearance.physicsIntensity,
                responsiveness = appearance.physicsResponsiveness
            )
            return
        }
        handle.removeAllViews()
        petView = null
        handle.background = when {
            mode == OverlayMode.PET -> null
            folded != null -> rounded(0xffa84e78.toInt(), 20)
            else -> rounded(0xffa84e78.toInt(), 0, 0xfff3c2d8.toInt()).apply { shape = GradientDrawable.OVAL }
        }
        val content = if (mode == OverlayMode.PET) {
            OverlayPetView(context, pet, folded).also {
                it.setPose(folded, appearance.motion, appearance.dockedMotion)
                it.setPhysicsConfig(
                    intensity = appearance.physicsIntensity,
                    responsiveness = appearance.physicsResponsiveness
                )
                petView = it
            }
        } else {
            labelView(when (folded) {
                FloatingEdge.LEFT -> "›"
                FloatingEdge.RIGHT -> "‹"
                null -> "＋"
            }, 25f * appearance.scale, Color.WHITE)
        }
        content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        handle.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    private fun addAction(label: String, action: () -> Unit) {
        val button = labelView(label, 15f * menuScale, 0xff472a40.toInt()).apply {
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
        val handleWidth = when {
            folded == null -> fullSize
            mode == OverlayMode.PET -> (fullSize * 0.62f).roundToInt().coerceAtLeast(1)
            else -> scaledDp(38).coerceAtMost(area.width)
        }
        val handleHeight = if (folded == null || mode == OverlayMode.PET) fullSize else scaledDp(48).coerceAtMost(area.height)
        val desired = anchor ?: FloatingPoint(area.right - fullSize - dp(20), area.top + area.height * 2 / 5)
        // Dock to the retained edge using the handle's current (folded or expanded) width.
        val edgeX = when (dockedEdge) {
            FloatingEdge.LEFT -> area.left
            FloatingEdge.RIGHT -> area.right - handleWidth
            null -> desired.x
        }
        val pos = area.clamp(edgeX, desired.y, handleWidth, handleHeight)
        anchor = pos
        if (mode == OverlayMode.PET && area.width > 0 && area.height > 0) {
            val normX = ((pos.x + handleWidth * 0.5f - area.left) / area.width).coerceIn(0f, 1f)
            val normY = ((pos.y + handleHeight * 0.5f - area.top) / area.height).coerceIn(0f, 1f)
            petView?.setScreenAnchor(normX, normY)
        }
        val showQuote = menuOpen && mode == OverlayMode.PET
        bubbleQuote.visibility = if (showQuote) View.VISIBLE else View.GONE
        val menuWidth = if (menuOpen) menuDp(if (showQuote) 186 else 164).coerceAtMost(area.width) else handleWidth
        val menuHeight = if (menuOpen) menuDp(if (showQuote) 132 else 104) else 0
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
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var lastMoveTimeMs = 0L
        private var smoothVx = 0f
        private var smoothVy = 0f
        private var start = FloatingPoint(0, 0)
        private var dragging = false

        private fun beginDrag() {
            val current = requireNotNull(anchor)
            // The folded handle is narrower than the expanded one; keep the expanded width on the same edge.
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
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    lastMoveTimeMs = SystemClock.uptimeMillis()
                    smoothVx = 0f
                    smoothVy = 0f
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
                        val now = SystemClock.uptimeMillis()
                        val dtSec = ((now - lastMoveTimeMs).coerceAtLeast(8L)) / 1000f
                        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
                        val instVx = ((event.rawX - lastRawX) / density) / dtSec
                        val instVy = ((event.rawY - lastRawY) / density) / dtSec
                        smoothVx = smoothVx * 0.55f + instVx * 0.45f
                        smoothVy = smoothVy * 0.55f + instVy * 0.45f
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        lastMoveTimeMs = now
                        petView?.onDragVelocity(smoothVx, smoothVy)
                        anchor = FloatingPoint(start.x + dx.roundToInt(), start.y + dy.roundToInt())
                        layout()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.performClick()
                    } else {
                        petView?.onDragEnd()
                        dockedEdge = area.edge(requireNotNull(anchor).x, fullSize, dp(34))
                        configureHandle()
                        layout()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        petView?.onDragEnd()
                    }
                }
            }
            return true
        }
    }
}

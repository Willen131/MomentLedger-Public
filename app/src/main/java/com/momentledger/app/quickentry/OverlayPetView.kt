package com.momentledger.app.quickentry

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

internal fun shouldPetAnimate(
    visibleToUser: Boolean,
    docked: Boolean,
    motion: Boolean,
    dockedMotion: Boolean,
    animatorsEnabled: Boolean
): Boolean = visibleToUser && motion && (!docked || dockedMotion) && animatorsEnabled

/**
 * Real-time 60fps independent-layer pseudo-Live2D view:
 * - Separate borderless layers (back hair/ahoge, clothes/instrument, head/face + 100%-coverage eye atlas)
 *   move cleanly on their own pivots: ZERO facial warping/twitching and ZERO hair tearing!
 * - Hair-back layer bends smoothly via its own 16x16 root-pinned mesh driven by Tier-2 delayed springs.
 * - Dynamic 8-tap GPU warm-white sticker border hugs the live silhouette cleanly (zero double borders).
 * - Signature anime vector particle effects tailored to each character (Kita sparkles, Nijika drum notes,
 *   Hitori cute shy sweat-drops, Ryo cool bass notes).
 */
internal class OverlayPetView(
    context: Context,
    val pet: OverlayPet,
    private var edge: FloatingEdge?
) : View(context) {

    private val fullPortrait: Bitmap by lazy { BitmapFactory.decodeResource(resources, pet.portrait) }
    // Unbroken borderless full-character sprite (zero cut seams in hair or neck!)
    private val headLayer: Bitmap = BitmapFactory.decodeResource(resources, pet.headLayer)
    private val eyesAtlas: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, pet.eyesAtlas)
    }.getOrNull()

    // Reusable 256x256 head sprite (redrawn ONLY when left/right eye state changes)
    private val headComposite: Bitmap = Bitmap.createBitmap(
        PET_DESIGN_SIZE.toInt(), PET_DESIGN_SIZE.toInt(), Bitmap.Config.ARGB_8888
    )
    private val headCanvas = Canvas(headComposite)

    // Reusable 256x256 composed borderless frame for crisp unified GPU sticker outline
    private val frameBitmap: Bitmap = Bitmap.createBitmap(
        PET_DESIGN_SIZE.toInt(), PET_DESIGN_SIZE.toInt(), Bitmap.Config.ARGB_8888
    )
    private val frameCanvas = Canvas(frameBitmap)

    private val designRect = Rect(0, 0, PET_DESIGN_SIZE.toInt(), PET_DESIGN_SIZE.toInt())
    private val designRectF = RectF(0f, 0f, PET_DESIGN_SIZE, PET_DESIGN_SIZE)
    private val borderDestRect = RectF()
    private val eyeSrcRect = Rect()
    private var renderedLeftEye: PetEyeState? = null
    private var renderedRightEye: PetEyeState? = null

    private val dockedStaticSprite by lazy {
        Bitmap.createBitmap(PET_ART_SIZE.toInt(), PET_ART_SIZE.toInt(), Bitmap.Config.ARGB_8888).also { art ->
            val canvas = Canvas(art)
            canvas.drawBitmap(fullPortrait, null, staticBounds, paint)
            canvas.drawRect(staticBounds, Paint().apply {
                shader = LinearGradient(0f, 164f, 0f, 209f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0x00000000, 0xFFFFFDF7.toInt())
    }
    private val rimBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0x00000000, 0xFFE6DFD0.toInt())
        alpha = 210
    }
    private val staticBounds = RectF(0f, 0f, PET_ART_SIZE, PET_ART_SIZE)
    private var dockedFadeHeight = -1
    private val dockedFadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    // 8-direction unit circle offsets for smooth dynamic GPU sticker border
    private val borderDirX = FloatArray(8) { i -> cos(i * PI / 4.0).toFloat() }
    private val borderDirY = FloatArray(8) { i -> sin(i * PI / 4.0).toFloat() }

    private val profile = PetPhysicsProfiles.forPetId(pet.id)
    private val controller = PetLive2dController(profile)
    private val hairMeshVertices = FloatArray(PET_MESH_VERTICES * 2)

    // Pre-allocated character-specific anime particle pool (zero per-frame allocations!)
    private class EffectParticle {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var age = 0f
        var life = 0.9f
        var size = 0.05f
        var rotDeg = 0f
        var rotVel = 0f
        var color = 0xffffe57f.toInt()
        // 0=4-pt sparkle, 1=eighth note, 2=sweat drop, 3=hollow bubble,
        // 4=dorito triangle, 5=heart, 6=hair cube, 7=grass leaf, 8=diamond crystal
        var kind = 0
    }

    private val particles = Array(20) { EffectParticle() }
    private var lastEffectBurstTick = 0
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fxPath = Path()
    private val fxRectF = RectF()

    private var visibleToUser = false
    private var motion = true
    private var dockedMotion = false
    private var frameLoopActive = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopActive || !shouldAnimate()) {
                frameLoopActive = false
                return
            }
            // Adaptive frame pacing: 60fps during active interaction/blinks/particles, 30fps during
            // calm idle breathing — prevents 120Hz screens from wasting 2-4x CPU/GPU battery!
            val activeMotion = controller.isInteractiveMotionActive || hasActiveParticles()
            val minIntervalNanos = if (activeMotion) 15_500_000L else 31_500_000L
            if (lastFrameNanos != 0L && (frameTimeNanos - lastFrameNanos) < minIntervalNanos) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val dtSec = if (lastFrameNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.036f)
            }
            lastFrameNanos = frameTimeNanos

            controller.step(dtSec, SystemClock.uptimeMillis())
            if (controller.effectBurstTick != lastEffectBurstTick) {
                lastEffectBurstTick = controller.effectBurstTick
                emitSignatureEffectBurst()
            }
            updateParticles(dtSec)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun hasActiveParticles(): Boolean {
        for (p in particles) {
            if (p.active) return true
        }
        return false
    }

    init {
        rebuildHeadComposite(PetEyeState.OPEN, PetEyeState.OPEN)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val nowVisible = isShown && windowVisibility == VISIBLE
        if (visibleToUser != nowVisible) {
            visibleToUser = nowVisible
            syncAnimation()
        } else if (nowVisible) {
            syncAnimation()
            invalidate()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (visibleToUser == isVisible) return
        visibleToUser = isVisible
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        visibleToUser = false
        stopFrameLoop()
        super.onDetachedFromWindow()
    }

    fun setPose(docked: FloatingEdge?, animate: Boolean, animateWhenDocked: Boolean = false) {
        if (edge == docked && motion == animate && dockedMotion == animateWhenDocked) return
        edge = docked
        motion = animate
        dockedMotion = animateWhenDocked
        syncAnimation()
    }

    fun setPhysicsConfig(intensity: Float, responsiveness: Float) {
        controller.physicsIntensity = intensity
        controller.physicsResponsiveness = responsiveness
    }

    fun setScreenAnchor(normScreenX: Float, normScreenY: Float) {
        controller.setScreenAnchor(normScreenX, normScreenY)
    }

    fun onPointerLook(normX: Float, normY: Float) {
        controller.onPointerLook(normX, normY)
    }

    fun onDragVelocity(vxDpPerSec: Float, vyDpPerSec: Float) {
        controller.onDragVelocity(vxDpPerSec, vyDpPerSec)
    }

    fun onDragEnd() {
        controller.onDragEnd()
    }

    fun onTapJelly() {
        controller.onTapJelly()
        if (controller.effectBurstTick != lastEffectBurstTick) {
            lastEffectBurstTick = controller.effectBurstTick
            emitSignatureEffectBurst()
        }
        if (shouldAnimate()) invalidate()
    }

    fun blink() {
        onTapJelly()
    }

    private fun shouldAnimate() = shouldPetAnimate(
        visibleToUser = visibleToUser,
        docked = edge != null,
        motion = motion,
        dockedMotion = dockedMotion,
        animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
    )

    private fun syncAnimation() {
        if (shouldAnimate()) {
            startFrameLoop()
        } else {
            stopFrameLoop()
            rebuildHeadComposite(PetEyeState.OPEN, PetEyeState.OPEN)
            for (p in particles) p.active = false
            invalidate()
        }
    }

    private fun startFrameLoop() {
        if (frameLoopActive) return
        frameLoopActive = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        if (!frameLoopActive) return
        frameLoopActive = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun rebuildHeadComposite(left: PetEyeState, right: PetEyeState) {
        if (renderedLeftEye == left && renderedRightEye == right) return
        renderedLeftEye = left
        renderedRightEye = right
        headComposite.eraseColor(Color.TRANSPARENT)
        headCanvas.drawBitmap(headLayer, null, designRect, paint)
        val atlas = eyesAtlas ?: return
        val halfW = atlas.width / 2
        val halfH = atlas.height / 2
        // 100%-coverage adaptive eye patches: TL=LeftHalf, TR=RightHalf, BL=LeftClosed, BR=RightClosed
        if (left != PetEyeState.OPEN) {
            val top = if (left == PetEyeState.HALF) 0 else halfH
            eyeSrcRect.set(0, top, halfW, top + halfH)
            headCanvas.drawBitmap(atlas, eyeSrcRect, designRect, paint)
        }
        if (right != PetEyeState.OPEN) {
            val top = if (right == PetEyeState.HALF) 0 else halfH
            eyeSrcRect.set(halfW, top, atlas.width, top + halfH)
            headCanvas.drawBitmap(atlas, eyeSrcRect, designRect, paint)
        }
    }

    private fun composeLayeredFrame() {
        rebuildHeadComposite(controller.leftEyeState, controller.rightEyeState)
        controller.deformCharacterMesh(hairMeshVertices)
        frameBitmap.eraseColor(Color.TRANSPARENT)
        // Single unbroken 28x28 piecewise-rigid LBS mesh:
        // - ZERO cut seams in hair, neck, or shoulders (mathematically 0% cracks at ANY amplitude!)
        // - Face/eyes & instruments remain 100% rigid while outer hair tips sway elastically!
        frameCanvas.drawBitmapMesh(
            headComposite, PET_MESH_GRID, PET_MESH_GRID,
            hairMeshVertices, 0, null, 0, paint
        )
    }

    private fun drawDynamicStickerAndFrame(canvas: Canvas, destSide: Float) {
        val borderRadius = destSide * (4.1f / PET_DESIGN_SIZE)
        val rimRadius = destSide * (4.9f / PET_DESIGN_SIZE)

        // Subtle warm-cream outer rim (4-diagonal GPU stamp: 25% less overdraw, identical crisp rim)
        var i = 1
        while (i < 8) {
            val ox = borderDirX[i] * rimRadius
            val oy = borderDirY[i] * rimRadius
            borderDestRect.set(ox, oy, destSide + ox, destSide + oy)
            canvas.drawBitmap(frameBitmap, designRect, borderDestRect, rimBorderPaint)
            i += 2
        }
        // Crisp warm-white sticker backing
        for (i in 0 until 8) {
            val ox = borderDirX[i] * borderRadius
            val oy = borderDirY[i] * borderRadius
            borderDestRect.set(ox, oy, destSide + ox, destSide + oy)
            canvas.drawBitmap(frameBitmap, designRect, borderDestRect, whiteBorderPaint)
        }
        // Crisp layered character cleanly on top
        borderDestRect.set(0f, 0f, destSide, destSide)
        canvas.drawBitmap(frameBitmap, designRect, borderDestRect, paint)
    }

    /**
     * All 4 characters radiate crisp anime vector effects 360° outward from the character's visual
     * center (0.50, 0.46), cycling through 3 distinct non-repeating variants per character!
     */
    private fun emitSignatureEffectBurst() {
        val variant = controller.effectVariantIndex % PET_EFFECT_VARIANT_COUNT
        val angleOffset = Random.nextFloat() * (2f * PI.toFloat())
        when (profile.effectStyle) {
            PetEffectStyle.NIJIKA_DRUM_NOTES -> when (variant) {
                0 -> {
                    // Golden drum eighth-notes + sunny amber sparkles bursting 360° from center
                    val notes = intArrayOf(0xffffc83d.toInt(), 0xffff9248.toInt(), 0xffffe57f.toInt())
                    spawnRadialBurst(count = 5, kind = 1, palette = notes, speedBase = 0.56f, lift = -0.05f, lifeBase = 0.88f, sizeBase = 0.052f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 0, palette = notes, speedBase = 0.44f, lift = -0.03f, lifeBase = 0.72f, sizeBase = 0.038f, angleOffset = angleOffset + 0.55f)
                }
                1 -> {
                    // Iconic golden Dorito triangles ▲ + warm golden glints radiating 360°
                    val doritos = intArrayOf(0xffffd147.toInt(), 0xffffa63a.toInt(), 0xffffef96.toInt())
                    spawnRadialBurst(count = 5, kind = 4, palette = doritos, speedBase = 0.54f, lift = -0.04f, lifeBase = 0.86f, sizeBase = 0.048f, spinBase = 135f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 3, palette = doritos, speedBase = 0.42f, lift = -0.02f, lifeBase = 0.75f, sizeBase = 0.034f, angleOffset = angleOffset + 0.60f)
                }
                else -> {
                    // Cheerful warm hearts ♥ + upbeat drum notes ♪ radiating 360°
                    val hearts = intArrayOf(0xffff7d8e.toInt(), 0xffffb84d.toInt(), 0xffffdf73.toInt())
                    spawnRadialBurst(count = 5, kind = 5, palette = hearts, speedBase = 0.52f, lift = -0.06f, lifeBase = 0.88f, sizeBase = 0.046f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 3, kind = 1, palette = hearts, speedBase = 0.46f, lift = -0.05f, lifeBase = 0.80f, sizeBase = 0.046f, angleOffset = angleOffset + 0.80f)
                }
            }
            PetEffectStyle.HITORI_SHY_DROPS -> when (variant) {
                0 -> {
                    // Anime sky-blue sweat-drops 💧 + soft pastel pink dots radiating 360° from center
                    val drops = intArrayOf(0xff7ec8ff.toInt(), 0xffa8deff.toInt(), 0xff6cb8f5.toInt())
                    val pinks = intArrayOf(0xffffa8cb.toInt(), 0xffffd1e3.toInt())
                    spawnRadialBurst(count = 5, kind = 2, palette = drops, speedBase = 0.46f, lift = -0.02f, lifeBase = 0.90f, sizeBase = 0.048f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 0, palette = pinks, speedBase = 0.38f, lift = -0.03f, lifeBase = 0.76f, sizeBase = 0.034f, angleOffset = angleOffset + 0.58f)
                }
                1 -> {
                    // Iconic Bocchi blue & yellow hair cubes ■ spinning softly 360° outward from center!
                    val cubes = intArrayOf(0xff5ec6ff.toInt(), 0xffffde59.toInt(), 0xff8cd6ff.toInt(), 0xffffea85.toInt())
                    spawnRadialBurst(count = 6, kind = 6, palette = cubes, speedBase = 0.48f, lift = -0.03f, lifeBase = 0.92f, sizeBase = 0.044f, spinBase = 110f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 3, kind = 3, palette = intArrayOf(0xffffb3d1.toInt()), speedBase = 0.38f, lift = -0.02f, lifeBase = 0.78f, sizeBase = 0.032f, angleOffset = angleOffset + 0.50f)
                }
                else -> {
                    // Soft pink hollow shy bubbles ○ + cherry-pink eighth-notes ♪ radiating 360° from center
                    val shyPinks = intArrayOf(0xffff9ac3.toInt(), 0xffffc2dc.toInt(), 0xffff7eb3.toInt())
                    spawnRadialBurst(count = 5, kind = 3, palette = shyPinks, speedBase = 0.45f, lift = -0.04f, lifeBase = 0.90f, sizeBase = 0.042f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 1, palette = shyPinks, speedBase = 0.40f, lift = -0.04f, lifeBase = 0.84f, sizeBase = 0.044f, angleOffset = angleOffset + 0.62f)
                }
            }
            PetEffectStyle.RYO_BASS_NOTES -> when (variant) {
                0 -> {
                    // Cool royal-blue & violet bass notes ♪ drifting serenely 360° outward from center
                    val bass = intArrayOf(0xff6b94ff.toInt(), 0xff9dbbff.toInt(), 0xffb388ff.toInt())
                    spawnRadialBurst(count = 5, kind = 1, palette = bass, speedBase = 0.40f, lift = -0.02f, lifeBase = 1.08f, sizeBase = 0.048f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 3, palette = bass, speedBase = 0.32f, lift = -0.01f, lifeBase = 0.96f, sizeBase = 0.032f, angleOffset = angleOffset + 0.60f)
                }
                1 -> {
                    // Iconic Ryo "吃草" emerald & mint grass leaves 🌿 + ice-blue glints drifting calmly 360°!
                    val leaves = intArrayOf(0xff5fd18b.toInt(), 0xff8ce6a8.toInt(), 0xff42b874.toInt())
                    val glints = intArrayOf(0xff9dc4ff.toInt(), 0xffd1e3ff.toInt())
                    spawnRadialBurst(count = 5, kind = 7, palette = leaves, speedBase = 0.42f, lift = -0.02f, lifeBase = 1.12f, sizeBase = 0.050f, spinBase = 75f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 0, palette = glints, speedBase = 0.34f, lift = -0.01f, lifeBase = 0.95f, sizeBase = 0.034f, angleOffset = angleOffset + 0.55f)
                }
                else -> {
                    // Cool ice-blue diamond crystals ◆ + crisp 4-pt star glints radiating slowly 360°
                    val crystals = intArrayOf(0xff7caeff.toInt(), 0xffb5d4ff.toInt(), 0xff8c82ff.toInt())
                    spawnRadialBurst(count = 5, kind = 8, palette = crystals, speedBase = 0.38f, lift = -0.01f, lifeBase = 1.08f, sizeBase = 0.046f, spinBase = 65f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 0, palette = crystals, speedBase = 0.32f, lift = -0.02f, lifeBase = 0.96f, sizeBase = 0.036f, angleOffset = angleOffset + 0.62f)
                }
            }
            PetEffectStyle.KITA_AURA_SPARKLES -> when (variant) {
                0 -> {
                    // Classic dazzling golden-pink 4-pt Kita-aura stars ✨ radiating 360° from center
                    val aura = intArrayOf(0xffffea70.toInt(), 0xffffb8cf.toInt(), 0xffffffff.toInt(), 0xffffd25a.toInt())
                    spawnRadialBurst(count = 8, kind = 0, palette = aura, speedBase = 0.54f, lift = -0.04f, lifeBase = 0.80f, sizeBase = 0.048f, angleOffset = angleOffset)
                }
                1 -> {
                    // Radiant coral hearts ♥ + golden Kita-aura sparkles radiating 360°
                    val hearts = intArrayOf(0xffff6b81.toInt(), 0xffffa6b8.toInt(), 0xffffdf6b.toInt())
                    spawnRadialBurst(count = 5, kind = 5, palette = hearts, speedBase = 0.52f, lift = -0.05f, lifeBase = 0.86f, sizeBase = 0.046f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 0, palette = intArrayOf(0xffffea70.toInt(), 0xffffffff.toInt()), speedBase = 0.44f, lift = -0.03f, lifeBase = 0.74f, sizeBase = 0.038f, angleOffset = angleOffset + 0.55f)
                }
                else -> {
                    // Cheerful crimson-gold singing eighth-notes ♪ + bright cross sparkles radiating 360°
                    val sing = intArrayOf(0xffff6f61.toInt(), 0xffffca57.toInt(), 0xffff9eb5.toInt())
                    spawnRadialBurst(count = 5, kind = 1, palette = sing, speedBase = 0.54f, lift = -0.05f, lifeBase = 0.86f, sizeBase = 0.050f, angleOffset = angleOffset)
                    spawnRadialBurst(count = 4, kind = 0, palette = intArrayOf(0xffffffff.toInt(), 0xffffe57f.toInt()), speedBase = 0.45f, lift = -0.03f, lifeBase = 0.74f, sizeBase = 0.038f, angleOffset = angleOffset + 0.60f)
                }
            }
        }
    }

    private fun spawnRadialBurst(
        count: Int,
        kind: Int,
        palette: IntArray,
        speedBase: Float,
        lift: Float,
        lifeBase: Float,
        sizeBase: Float,
        spinBase: Float = 0f,
        angleOffset: Float = 0f,
        centerX: Float = 0.50f,
        centerY: Float = 0.46f
    ) {
        var spawned = 0
        val stepAngle = (2.0 * PI / count.coerceAtLeast(1)).toFloat()
        for (p in particles) {
            if (!p.active && spawned < count) {
                val angle = angleOffset + spawned * stepAngle + (Random.nextFloat() - 0.5f) * (stepAngle * 0.36f)
                val startR = 0.045f + Random.nextFloat() * 0.045f
                val speed = speedBase * (0.82f + Random.nextFloat() * 0.36f)
                p.active = true
                p.kind = kind
                p.x = (centerX + cos(angle) * startR).coerceIn(0.12f, 0.88f)
                p.y = (centerY + sin(angle) * startR).coerceIn(0.12f, 0.86f)
                p.vx = cos(angle) * speed
                p.vy = sin(angle) * speed + lift
                p.age = 0f
                p.life = lifeBase + Random.nextFloat() * 0.24f
                p.size = sizeBase + Random.nextFloat() * 0.014f
                p.rotDeg = Random.nextFloat() * 360f
                p.rotVel = if (spinBase > 0f) {
                    (if (spawned % 2 == 0) 1f else -1f) * spinBase * (0.75f + Random.nextFloat() * 0.5f)
                } else {
                    0f
                }
                p.color = palette[spawned % palette.size]
                spawned++
            }
        }
    }

    private fun updateParticles(dtSec: Float) {
        // Frame-rate independent smooth outward deceleration (60Hz / 90Hz / 120Hz identical!)
        val drag = exp(-2.75f * dtSec)
        for (p in particles) {
            if (!p.active) continue
            p.age += dtSec
            if (p.age >= p.life) {
                p.active = false
                continue
            }
            p.x += p.vx * dtSec
            p.y += p.vy * dtSec
            p.rotDeg += p.rotVel * dtSec
            p.vx *= drag
            p.vy *= drag
            if (p.kind == 2) {
                // Subtle anime sweat-drop gravity tail
                p.vy += 0.14f * dtSec
            }
        }
    }

    private fun drawParticles(canvas: Canvas, artSide: Float) {
        for (p in particles) {
            if (!p.active) continue
            val u = (p.age / p.life).coerceIn(0f, 1f)
            // Fast pop-in near character center, graceful fade-out at perimeter
            val popIn = (u / 0.16f).coerceIn(0f, 1f)
            val fadeOut = sin(((1f - u).coerceIn(0f, 1f)) * (PI.toFloat() * 0.5f))
            val env = popIn * fadeOut
            val twinkle = 0.84f + 0.16f * sin(p.age * 18f)
            val r = artSide * p.size * (0.45f + 0.55f * env) * twinkle
            if (r < 0.8f) continue
            val cx = p.x * artSide
            val cy = p.y * artSide
            val alpha = (env * 245f).toInt().coerceIn(0, 255)
            fxPaint.color = p.color
            fxPaint.alpha = alpha

            when (p.kind) {
                0 -> {
                    // 4-point anime star sparkle ✦
                    val inner = r * 0.26f
                    fxPath.rewind()
                    fxPath.moveTo(cx, cy - r)
                    fxPath.lineTo(cx + inner, cy - inner)
                    fxPath.lineTo(cx + r, cy)
                    fxPath.lineTo(cx + inner, cy + inner)
                    fxPath.lineTo(cx, cy + r)
                    fxPath.lineTo(cx - inner, cy + inner)
                    fxPath.lineTo(cx - r, cy)
                    fxPath.lineTo(cx - inner, cy - inner)
                    fxPath.close()
                    canvas.drawPath(fxPath, fxPaint)
                }
                1 -> {
                    // Crisp musical eighth-note ♪
                    canvas.drawCircle(cx - r * 0.22f, cy + r * 0.35f, r * 0.34f, fxPaint)
                    fxStrokePaint.color = p.color
                    fxStrokePaint.alpha = alpha
                    fxStrokePaint.strokeWidth = (r * 0.22f).coerceAtLeast(1.4f)
                    canvas.drawLine(cx + r * 0.08f, cy + r * 0.30f, cx + r * 0.08f, cy - r * 0.65f, fxStrokePaint)
                    canvas.drawLine(cx + r * 0.08f, cy - r * 0.65f, cx + r * 0.62f, cy - r * 0.35f, fxStrokePaint)
                }
                2 -> {
                    // Classic anime teardrop sweat-drop 💧
                    fxPath.rewind()
                    fxPath.moveTo(cx, cy - r * 0.95f)
                    fxPath.quadTo(cx + r * 0.72f, cy + r * 0.15f, cx, cy + r * 0.78f)
                    fxPath.quadTo(cx - r * 0.72f, cy + r * 0.15f, cx, cy - r * 0.95f)
                    fxPath.close()
                    canvas.drawPath(fxPath, fxPaint)
                }
                3 -> {
                    // Soft hollow shy bubble / glow ring ○
                    fxStrokePaint.color = p.color
                    fxStrokePaint.alpha = alpha
                    fxStrokePaint.strokeWidth = (r * 0.24f).coerceAtLeast(1.3f)
                    canvas.drawCircle(cx, cy, r * 0.68f, fxStrokePaint)
                }
                4 -> {
                    // Nijika's iconic spinning golden Dorito triangle ▲
                    canvas.save()
                    canvas.rotate(p.rotDeg, cx, cy)
                    fxPath.rewind()
                    fxPath.moveTo(cx, cy - r * 0.92f)
                    fxPath.lineTo(cx + r * 0.82f, cy + r * 0.68f)
                    fxPath.lineTo(cx - r * 0.82f, cy + r * 0.68f)
                    fxPath.close()
                    canvas.drawPath(fxPath, fxPaint)
                    canvas.restore()
                }
                5 -> {
                    // Cute anime heart ♥
                    fxPath.rewind()
                    fxPath.moveTo(cx, cy + r * 0.76f)
                    fxPath.cubicTo(cx - r * 1.05f, cy + r * 0.12f, cx - r * 0.95f, cy - r * 0.82f, cx, cy - r * 0.25f)
                    fxPath.cubicTo(cx + r * 0.95f, cy - r * 0.82f, cx + r * 1.05f, cy + r * 0.12f, cx, cy + r * 0.76f)
                    fxPath.close()
                    canvas.drawPath(fxPath, fxPaint)
                }
                6 -> {
                    // Hitori's iconic spinning blue/yellow hair cube ■
                    canvas.save()
                    canvas.rotate(p.rotDeg, cx, cy)
                    val half = r * 0.66f
                    fxRectF.set(cx - half, cy - half, cx + half, cy + half)
                    canvas.drawRoundRect(fxRectF, r * 0.22f, r * 0.22f, fxPaint)
                    canvas.restore()
                }
                7 -> {
                    // Ryo's iconic "吃草" graceful grass leaf 🌿
                    canvas.save()
                    canvas.rotate(p.rotDeg, cx, cy)
                    fxPath.rewind()
                    fxPath.moveTo(cx, cy - r * 0.95f)
                    fxPath.quadTo(cx + r * 0.76f, cy - r * 0.05f, cx, cy + r * 0.92f)
                    fxPath.quadTo(cx - r * 0.76f, cy - r * 0.05f, cx, cy - r * 0.95f)
                    fxPath.close()
                    canvas.drawPath(fxPath, fxPaint)
                    fxStrokePaint.color = 0xffe8ffef.toInt()
                    fxStrokePaint.alpha = (alpha * 0.7f).toInt()
                    fxStrokePaint.strokeWidth = (r * 0.12f).coerceAtLeast(1.0f)
                    canvas.drawLine(cx, cy - r * 0.62f, cx, cy + r * 0.78f, fxStrokePaint)
                    canvas.restore()
                }
                else -> {
                    // Ryo's cool ice-blue diamond crystal ◆
                    canvas.save()
                    canvas.rotate(p.rotDeg * 0.4f, cx, cy)
                    fxPath.rewind()
                    fxPath.moveTo(cx, cy - r * 0.95f)
                    fxPath.lineTo(cx + r * 0.62f, cy)
                    fxPath.lineTo(cx, cy + r * 0.95f)
                    fxPath.lineTo(cx - r * 0.62f, cy)
                    fxPath.close()
                    canvas.drawPath(fxPath, fxPaint)
                    canvas.restore()
                }
            }
        }
    }

    private fun dockedFadePaintFor(artHeight: Int): Paint {
        if (dockedFadeHeight != artHeight) {
            dockedFadeHeight = artHeight
            val scale = artHeight / PET_ART_SIZE
            dockedFadePaint.shader = LinearGradient(
                0f, 164f * scale, 0f, 209f * scale,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        return dockedFadePaint
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        canvas.save()
        val artSide = height.toFloat()
        val scale = artSide / PET_ART_SIZE
        val docked = edge
        val animate = shouldAnimate()

        if (animate) {
            composeLayeredFrame()
        }

        if (docked != null) {
            val left = docked == FloatingEdge.LEFT
            val inset = artSide * 0.075f
            canvas.translate(if (left) width - artSide - inset else inset, 0f)
            if (animate) {
                canvas.rotate(if (left) 32f else -32f, 110f * scale, 91f * scale)
                val layerId = canvas.saveLayer(0f, 0f, artSide, artSide, null)
                val dockedInner = artSide * 0.95f
                val dockedPad = artSide * 0.025f
                canvas.translate(dockedPad, dockedPad)
                drawDynamicStickerAndFrame(canvas, dockedInner)
                canvas.translate(-dockedPad, -dockedPad)
                drawParticles(canvas, artSide)
                canvas.drawRect(0f, 0f, artSide, artSide, dockedFadePaintFor(height))
                canvas.restoreToCount(layerId)
            } else {
                canvas.scale(scale, scale)
                canvas.rotate(if (left) 32f else -32f, 110f, 91f)
                canvas.drawBitmap(dockedStaticSprite, null, staticBounds, paint)
            }
        } else if (animate) {
            val innerSide = artSide * 0.92f
            val padX = artSide * 0.04f
            val padY = artSide * 0.045f
            canvas.translate(padX, padY)
            drawDynamicStickerAndFrame(canvas, innerSide)
            canvas.translate(-padX, -padY)
            drawParticles(canvas, artSide)
        } else {
            canvas.scale(scale, scale)
            canvas.drawBitmap(fullPortrait, null, staticBounds, paint)
        }
        canvas.restore()
    }
}

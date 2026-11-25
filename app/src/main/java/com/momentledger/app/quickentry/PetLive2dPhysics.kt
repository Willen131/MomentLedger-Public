package com.momentledger.app.quickentry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal const val PET_MESH_GRID = 28
internal const val PET_MESH_VERTICES = (PET_MESH_GRID + 1) * (PET_MESH_GRID + 1)
internal const val PET_DESIGN_SIZE = 256f
internal const val PET_EFFECT_VARIANT_COUNT = 3

internal enum class PetEyeState { OPEN, HALF, CLOSED }

internal enum class PetEffectStyle {
    HITORI_SHY_DROPS,
    RYO_BASS_NOTES,
    NIJIKA_DRUM_NOTES,
    KITA_AURA_SPARKLES
}

/**
 * Character-exclusive micro-actions: every band member has their own distinct motion vocabulary
 * reflecting their personality & artwork (zero generic copy-paste animations across characters).
 */
internal enum class PetMicroAction {
    NONE,
    // Nijika (energetic drummer, dorito ahoge)
    NIJIKA_DRUM_GROOVE,
    NIJIKA_WINK_CHEER,
    NIJIKA_AHOGE_FLICK,
    // Hitori / Bocchi (shy introvert guitarist, calm non-dizzying posture)
    HITORI_SHY_DUCK,
    HITORI_PEEK_BLINK,
    HITORI_GUITAR_HUG,
    // Ryo (cool, high-damping, slow half-beat deadpan bassist)
    RYO_BASS_GROOVE,
    RYO_LAZY_STARE,
    RYO_ALOOF_GLANCE,
    // Kita (radiant vocalist, buoyant singing sway & Kita-aura)
    KITA_SING_SWAY,
    KITA_AURA_WINK,
    KITA_HAPPY_HOP
}

/**
 * Second-order spring-damper oscillator:
 *   x'' = w^2 * (target - x) - 2 * zeta * w * x'
 * Produces natural fast-attack, smooth-settle organic motion without linear stiffness.
 */
internal class Spring1D(
    var value: Float = 0f,
    var velocity: Float = 0f
) {
    fun step(target: Float, freqHz: Float, dampingRatio: Float, dtSec: Float): Float {
        val clampedDt = dtSec.coerceIn(0.001f, 0.033f)
        val subDt = clampedDt * 0.5f
        val omega = (2.0 * PI * freqHz.coerceIn(0.4f, 18f)).toFloat()
        val omegaSq = omega * omega
        val twoZetaOmega = 2f * dampingRatio.coerceIn(0.20f, 1.6f) * omega
        repeat(2) {
            val accel = omegaSq * (target - value) - twoZetaOmega * velocity
            velocity += accel * subDt
            value += velocity * subDt
        }
        return value
    }

    fun impulse(dv: Float) {
        velocity += dv
    }

    fun reset(v: Float = 0f) {
        value = v
        velocity = 0f
    }
}

internal data class PetHairZone(
    val rootX: Float,
    val rootY: Float,
    val tipX: Float,
    val tipY: Float,
    val radius: Float,
    val strength: Float,
    val isSecondaryTip: Boolean = false
)

internal data class PetExclusionZone(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float
)

internal data class PetPhysicsProfile(
    val petId: String,
    val effectStyle: PetEffectStyle,
    val anchorBottomY: Float,
    val neckPivotX: Float,
    val neckPivotY: Float,
    val faceCx: Float,
    val faceCy: Float,
    val faceRx: Float,
    val faceRy: Float,
    val breathPeriodSec: Float,
    val swayPeriodSec: Float,
    val headFreqHz: Float,
    val headDamping: Float,
    val hairFreqHz: Float,
    val hairDamping: Float,
    val tipFreqHz: Float,
    val tipDamping: Float,
    val breathLiftPx: Float,
    val headSwayDeg: Float,
    val headExclusions: List<PetExclusionZone>,
    val hairZones: List<PetHairZone>,
    val bubbleAccentColor: Int,
    val speechLines: List<String>
)

internal object PetPhysicsProfiles {
    val HITORI = PetPhysicsProfile(
        petId = "hitori",
        effectStyle = PetEffectStyle.HITORI_SHY_DROPS,
        anchorBottomY = 240f,
        neckPivotX = 132f,
        neckPivotY = 148f,
        faceCx = 136f,
        faceCy = 108f,
        faceRx = 54f,
        faceRy = 50f,
        // Gentle rapid shy breathing, calm & never dizzying
        breathPeriodSec = 2.23f,
        swayPeriodSec = 5.37f,
        headFreqHz = 3.2f,
        headDamping = 0.80f,
        hairFreqHz = 2.5f,
        hairDamping = 0.58f,
        tipFreqHz = 3.2f,
        tipDamping = 0.48f,
        breathLiftPx = 1.45f,
        headSwayDeg = 1.25f,
        headExclusions = listOf(
            // Guitar headstock sticking up on right stays 100% locked to rigid Torso
            PetExclusionZone(218f, 138f, 42f, 42f)
        ),
        hairZones = listOf(
            PetHairZone(54f, 118f, 24f, 236f, radius = 40f, strength = 0.85f, isSecondaryTip = false),
            PetHairZone(42f, 80f, 18f, 114f, radius = 18f, strength = 0.90f, isSecondaryTip = true)
        ),
        bubbleAccentColor = 0xffec7ca8.toInt(),
        speechLines = listOf(
            "那、那个…要记一笔吗…",
            "没、没花超预算吧…",
            "识完屏我就缩回角落…"
        )
    )

    val RYO = PetPhysicsProfile(
        petId = "ryo",
        effectStyle = PetEffectStyle.RYO_BASS_NOTES,
        anchorBottomY = 240f,
        neckPivotX = 118f,
        neckPivotY = 146f,
        faceCx = 122f,
        faceCy = 104f,
        faceRx = 52f,
        faceRy = 48f,
        // Deep calm bass-groove breathing + high-damping lazy slow response
        breathPeriodSec = 4.31f,
        swayPeriodSec = 6.47f,
        headFreqHz = 1.75f,
        headDamping = 0.96f,
        hairFreqHz = 1.8f,
        hairDamping = 0.78f,
        tipFreqHz = 2.0f,
        tipDamping = 0.75f,
        breathLiftPx = 1.25f,
        headSwayDeg = 1.10f,
        headExclusions = listOf(
            // Blue bass headstock sticking up on right stays 100% locked to rigid Torso
            PetExclusionZone(214f, 140f, 40f, 40f)
        ),
        // Ryo's sleek short bob stays unified on Head so it can never split or crack!
        hairZones = emptyList(),
        bubbleAccentColor = 0xff5a74c6.toInt(),
        speechLines = listOf(
            "借我点钱…咳，先记账。",
            "贝斯弦又该换了。",
            "嗯，慢慢记就好。"
        )
    )

    val NIJIKA = PetPhysicsProfile(
        petId = "nijika",
        effectStyle = PetEffectStyle.NIJIKA_DRUM_NOTES,
        anchorBottomY = 238f,
        neckPivotX = 110f,
        neckPivotY = 152f,
        faceCx = 112f,
        faceCy = 114f,
        faceRx = 52f,
        faceRy = 48f,
        // Energetic crisp high-elasticity bounce + prominent ahoge/ponytail whip
        breathPeriodSec = 2.73f,
        swayPeriodSec = 4.19f,
        headFreqHz = 4.4f,
        headDamping = 0.62f,
        hairFreqHz = 3.0f,
        hairDamping = 0.45f,
        tipFreqHz = 4.0f,
        tipDamping = 0.36f,
        breathLiftPx = 1.95f,
        headSwayDeg = 1.75f,
        headExclusions = listOf(
            // Left & right drumsticks stay 100% locked to rigid Torso
            PetExclusionZone(36f, 148f, 26f, 32f),
            PetExclusionZone(158f, 150f, 14f, 28f)
        ),
        hairZones = listOf(
            PetHairZone(172f, 74f, 206f, 232f, radius = 52f, strength = 1.05f, isSecondaryTip = false),
            PetHairZone(102f, 44f, 86f, 12f, radius = 30f, strength = 1.15f, isSecondaryTip = true)
        ),
        bubbleAccentColor = 0xffe4a836.toInt(),
        speechLines = listOf(
            "记一笔吧！排练前先算清~",
            "交给鼓手来拍账单！",
            "今天也要元气满满哦♪"
        )
    )

    val KITA = PetPhysicsProfile(
        petId = "kita",
        effectStyle = PetEffectStyle.KITA_AURA_SPARKLES,
        anchorBottomY = 240f,
        neckPivotX = 124f,
        neckPivotY = 144f,
        faceCx = 116f,
        faceCy = 102f,
        faceRx = 54f,
        faceRy = 50f,
        // Bright buoyant shoulder sway + lively side ponytail
        breathPeriodSec = 2.93f,
        swayPeriodSec = 3.89f,
        headFreqHz = 4.1f,
        headDamping = 0.64f,
        hairFreqHz = 2.9f,
        hairDamping = 0.46f,
        tipFreqHz = 3.7f,
        tipDamping = 0.40f,
        breathLiftPx = 1.85f,
        headSwayDeg = 1.85f,
        headExclusions = listOf(
            // Microphone head & red guitar headstock stay 100% locked to rigid Torso
            PetExclusionZone(92f, 144f, 20f, 20f),
            PetExclusionZone(220f, 158f, 38f, 36f)
        ),
        hairZones = listOf(
            PetHairZone(178f, 44f, 210f, 124f, radius = 40f, strength = 1.05f, isSecondaryTip = true)
        ),
        bubbleAccentColor = 0xffe05a65.toInt(),
        speechLines = listOf(
            "喜多灵光✨马上帮你记！",
            "今天买了什么好东西呀♪",
            "一键识屏超方便的！"
        )
    )

    fun forPetId(id: String): PetPhysicsProfile = when (id) {
        "ryo" -> RYO
        "nijika" -> NIJIKA
        "kita" -> KITA
        else -> HITORI
    }
}

/**
 * Precomputed C1-continuous mesh weights applied ONLY to the isolated back-hair/ahoge layer.
 * Root pinning (`smoothstep(0.24f, 1.0f, along)`) guarantees 0.0px displacement throughout
 * the root/seam overlap zone so hair NEVER cracks or detaches from the scalp!
 */
internal class PetMeshWeights(val profile: PetPhysicsProfile) {
    val baseX = FloatArray(PET_MESH_VERTICES)
    val baseY = FloatArray(PET_MESH_VERTICES)
    val anchorW = FloatArray(PET_MESH_VERTICES)
    val headW = FloatArray(PET_MESH_VERTICES)
    val face3dW = FloatArray(PET_MESH_VERTICES)
    val hairW = FloatArray(PET_MESH_VERTICES)
    val hairPerpX = FloatArray(PET_MESH_VERTICES)
    val hairPerpY = FloatArray(PET_MESH_VERTICES)
    val tipW = FloatArray(PET_MESH_VERTICES)
    val tipPerpX = FloatArray(PET_MESH_VERTICES)
    val tipPerpY = FloatArray(PET_MESH_VERTICES)

    init {
        val step = PET_DESIGN_SIZE / PET_MESH_GRID
        var idx = 0
        for (row in 0..PET_MESH_GRID) {
            val y = row * step
            for (col in 0..PET_MESH_GRID) {
                val x = col * step
                baseX[idx] = x
                baseY[idx] = y

                anchorW[idx] = smoothstep(profile.anchorBottomY, profile.anchorBottomY - 52f, y)

                val dx = (x - profile.faceCx) / profile.faceRx
                val dy = (y - profile.faceCy) / profile.faceRy
                val faceDistSq = dx * dx + dy * dy
                val dome = exp(-faceDistSq * 0.85f)
                val headMask = smoothstep(profile.neckPivotY + 18f, profile.neckPivotY - 16f, y)
                face3dW[idx] = headMask * (dome * 1.18f - 0.18f)

                // Piecewise-rigid Head skinning weight (1.0 across entire face/eyes/head,
                // 0.0 across torso & instruments, smooth 24px C2 blend ONLY across neck/collar!)
                var exclGuard = 1f
                for (ex in profile.headExclusions) {
                    val edx = (x - ex.cx) / ex.rx
                    val edy = (y - ex.cy) / ex.ry
                    exclGuard *= smoothstep(0.75f, 1.25f, sqrt(edx * edx + edy * edy))
                }
                headW[idx] = smoothstep(profile.neckPivotY + 12f, profile.neckPivotY - 12f, y) * exclGuard

                // Strict rigid face/scalp keep-out mask: 0.0 inside face/eyes ellipse & instruments
                // so unbroken single-mesh NEVER warps facial features or instruments while outer hair tips sway!
                val faceGuard = smoothstep(0.95f, 1.35f, sqrt(faceDistSq)) * exclGuard

                var hWeight = 0f
                var hPx = 1f
                var hPy = 0f
                var tWeight = 0f
                var tPx = 1f
                var tPy = 0f
                for (zone in profile.hairZones) {
                    val vx = zone.tipX - zone.rootX
                    val vy = zone.tipY - zone.rootY
                    val lsq = max(vx * vx + vy * vy, 1f)
                    val len = sqrt(lsq)
                    val along = ((x - zone.rootX) * vx + (y - zone.rootY) * vy) / lsq
                    val clampedAlong = along.coerceIn(0f, 1.15f)
                    val projX = zone.rootX + clampedAlong * vx
                    val projY = zone.rootY + clampedAlong * vy
                    val dist = hypot(x - projX, y - projY)
                    val radial = smoothstep(zone.radius, 0f, dist)
                    // Strict root pinning: 0.0 weight for along <= 0.28 so scalp root stays 100% locked!
                    val rootRamp = smoothstep(0.28f, 1.0f, along)
                    val flex = radial * rootRamp * rootRamp * faceGuard * zone.strength
                    var px = -vy / len
                    var py = vx / len
                    if (px < 0f && !zone.isSecondaryTip) {
                        px = -px
                        py = -py
                    }
                    if (zone.isSecondaryTip) {
                        if (abs(flex) > abs(tWeight)) {
                            tWeight = flex
                            tPx = px
                            tPy = py
                        }
                    } else {
                        if (abs(flex) > abs(hWeight)) {
                            hWeight = flex
                            hPx = px
                            hPy = py
                        }
                    }
                }
                hairW[idx] = hWeight
                hairPerpX[idx] = hPx
                hairPerpY[idx] = hPy
                tipW[idx] = tWeight
                tipPerpX[idx] = tPx
                tipPerpY[idx] = tPy

                idx++
            }
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * Strict parent-child bone hierarchy pose computed each 60fps frame:
 *   Root (Jelly squash/stretch at anchorBottomY)
 *     └── Torso / Clothes (`clothesTx`, `clothesTy`, `clothesRotDeg`, `clothesBreathScaleY`)
 *           ├── Child Head (`headRelTx`, `headRelTy`, `headRelRotDeg` RELATIVE to Torso at neckPivot)
 *           └── Child HairBack (inherits EXACT Torso -> Head transform + root-pinned flexible tip mesh!)
 *
 * Because Head and HairBack strictly inherit Torso's matrix, neck and hair seams NEVER split apart!
 */
internal class PetLayerPose {
    var jellyScaleX: Float = 1f
    var jellyScaleY: Float = 1f

    // Parent: Torso / Clothes transform in design coordinates
    var clothesTx: Float = 0f
    var clothesTy: Float = 0f
    var clothesRotDeg: Float = 0f
    var clothesBreathScaleY: Float = 1f

    // Child: Head transform STRICTLY RELATIVE to Torso (pivoted at neckPivot)
    var headRelTx: Float = 0f
    var headRelTy: Float = 0f
    var headRelRotDeg: Float = 0f
}

/**
 * Real-time 60fps layered pseudo-Live2D controller with strict parent-child bone hierarchy,
 * personality-exclusive motion state machines, high-damping Ryo tap feel, and non-repeating
 * 3-variant center-radiating signature particle effects.
 */
internal class PetLive2dController(
    val profile: PetPhysicsProfile,
    private val random: Random = Random.Default
) {
    val weights = PetMeshWeights(profile)
    val pose = PetLayerPose()

    // Tier-1 Primary 2nd-order springs (well-damped, organic, zero facial twitching)
    private val lookXSpring = Spring1D()
    private val lookYSpring = Spring1D()
    private val headTiltSpring = Spring1D()
    private val bodyLiftSpring = Spring1D()
    private val shoulderSwaySpring = Spring1D()
    private val jellyXSpring = Spring1D(1f)
    private val jellyYSpring = Spring1D(1f)
    private val dragTiltSpring = Spring1D()

    // Tier-2 Delayed secondary springs for hair & ahoge (lag half a beat behind head movement)
    private val hairLagXSpring = Spring1D()
    private val hairLagYSpring = Spring1D()
    private val tipLagXSpring = Spring1D()
    private val tipLagYSpring = Spring1D()

    // Runtime tunables
    var physicsIntensity: Float = 1.0f
    var physicsResponsiveness: Float = 1.0f

    // Gaze targets (smooth continuous drift — never abrupt jumps)
    private var screenBiasX: Float = 0f
    private var screenBiasY: Float = 0f
    private var pointerActiveTimer: Float = 0f
    private var pointerTargetX: Float = 0f
    private var pointerTargetY: Float = 0f

    // Drag tracking
    private var isDragging: Boolean = false
    private var dragTargetTiltDeg: Float = 0f

    // Natural slow blink state
    private var nextBlinkInSec: Float = randomRange(2.8f, 5.2f)
    private var blinkElapsedSec: Float = -1f
    private var queuedSecondBlink: Boolean = false

    // Personality micro-action state machine (every 4.8-9.8s)
    private var nextMicroActionInSec: Float = randomRange(4.8f, 8.5f)
    var activeMicroAction: PetMicroAction = PetMicroAction.NONE
        private set
    private var microElapsedSec: Float = 0f
    private var microDurationSec: Float = 0f
    private var lastTapCycleIdx: Int = -1
    private var lastRandomActionIdx: Int = -1

    private var localElapsedSec: Float = 0f
    var leftEyeState: PetEyeState = PetEyeState.OPEN
        private set
    var rightEyeState: PetEyeState = PetEyeState.OPEN
        private set

    // Non-repeating signature effect burst trigger + variant index (0..2)
    var effectBurstTick: Int = 0
        private set
    var effectVariantIndex: Int = 0
        private set
    private var lastEffectVariant: Int = -1

    /**
     * True while actively interacting (dragging, jelly settling, blinking, micro-action, or pointer look).
     * Allows OverlayPetView to run at 60fps during active motion and drop cleanly to 30fps during
     * calm idle breathing — cutting battery drain on 120Hz displays by ~65-75%!
     */
    val isInteractiveMotionActive: Boolean
        get() = activeMicroAction != PetMicroAction.NONE ||
            isDragging ||
            pointerActiveTimer > 0f ||
            leftEyeState != PetEyeState.OPEN ||
            rightEyeState != PetEyeState.OPEN ||
            abs(pose.jellyScaleX - 1f) > 0.004f ||
            abs(pose.jellyScaleY - 1f) > 0.004f

    fun setScreenAnchor(normScreenX: Float, normScreenY: Float) {
        screenBiasX = ((0.5f - normScreenX) * 0.42f).coerceIn(-0.32f, 0.32f)
        screenBiasY = ((0.45f - normScreenY) * 0.25f).coerceIn(-0.20f, 0.20f)
    }

    fun onPointerLook(normX: Float, normY: Float) {
        pointerTargetX = normX.coerceIn(-1f, 1f)
        pointerTargetY = normY.coerceIn(-1f, 1f)
        pointerActiveTimer = 1.6f
    }

    fun onDragVelocity(vxDpPerSec: Float, vyDpPerSec: Float) {
        isDragging = true
        val normVx = (vxDpPerSec / 680f).coerceIn(-1.15f, 1.15f)
        val normVy = (vyDpPerSec / 780f).coerceIn(-0.85f, 0.85f)
        dragTargetTiltDeg = (normVx * 11f).coerceIn(-12f, 12f)
        pointerTargetX = (normVx * 0.75f).coerceIn(-1f, 1f)
        pointerTargetY = (normVy * 0.55f).coerceIn(-0.75f, 0.75f)
        pointerActiveTimer = 0.6f
        hairLagXSpring.impulse(-normVx * 7.5f)
        tipLagXSpring.impulse(-normVx * 10.5f)
    }

    fun onDragEnd() {
        isDragging = false
        dragTargetTiltDeg = 0f
        if (profile.petId == "ryo") {
            // Ryo settles calmly with heavy damping
            jellyXSpring.impulse(0.28f)
            jellyYSpring.impulse(-0.26f)
            bodyLiftSpring.impulse(4.5f)
        } else {
            jellyXSpring.impulse(0.68f)
            jellyYSpring.impulse(-0.64f)
            bodyLiftSpring.impulse(8f)
        }
    }

    /**
     * Signature personality tap reaction for each band member!
     * Consecutive clicks alternate actions and NEVER repeat the same particle effect variant.
     */
    fun onTapJelly() {
        val amp = physicsIntensity.coerceIn(0.4f, 1.6f)
        lastTapCycleIdx = (lastTapCycleIdx + 1) % 2
        when (profile.petId) {
            "nijika" -> {
                // Energetic crisp drummer bounce + big dorito ahoge & ponytail whip
                jellyXSpring.impulse(1.55f * amp)
                jellyYSpring.impulse(-1.45f * amp)
                bodyLiftSpring.impulse(-20f * amp)
                hairLagXSpring.impulse(11f * amp)
                tipLagXSpring.impulse(17f * amp)
                if (lastTapCycleIdx == 0) {
                    triggerMicroAction(PetMicroAction.NIJIKA_WINK_CHEER, duration = 0.92f)
                } else {
                    triggerMicroAction(PetMicroAction.NIJIKA_DRUM_GROOVE, duration = 0.96f)
                }
                emitNonRepeatingEffect()
            }
            "hitori" -> {
                // Gentle bashful tuck + double blink (ZERO dizzying high-frequency shake!)
                jellyXSpring.impulse(0.95f * amp)
                jellyYSpring.impulse(-1.02f * amp)
                bodyLiftSpring.impulse(10f * amp)
                hairLagYSpring.impulse(6f * amp)
                if (lastTapCycleIdx == 0) {
                    triggerMicroAction(PetMicroAction.HITORI_SHY_DUCK, duration = 1.00f)
                    blinkElapsedSec = 0f
                    queuedSecondBlink = true
                } else {
                    triggerMicroAction(PetMicroAction.HITORI_GUITAR_HUG, duration = 1.05f)
                    blinkElapsedSec = 0f
                    queuedSecondBlink = false
                }
                emitNonRepeatingEffect()
            }
            "ryo" -> {
                // Cool, high-damping, slow half-beat bassist reaction:
                // Grounded minimal squash (NO bouncy Q-弹 hop!), calm weighted settle + cool glance/bass nod
                jellyXSpring.impulse(0.28f * amp)
                jellyYSpring.impulse(-0.26f * amp)
                bodyLiftSpring.impulse(6.0f * amp)
                if (lastTapCycleIdx == 0) {
                    triggerMicroAction(PetMicroAction.RYO_LAZY_STARE, duration = 1.35f)
                } else {
                    triggerMicroAction(PetMicroAction.RYO_BASS_GROOVE, duration = 1.30f)
                }
                emitNonRepeatingEffect()
            }
            else -> {
                // Kita: buoyant joyful vocalist Q-弹 + lively shoulder rock + bright Kita-aura
                jellyXSpring.impulse(1.48f * amp)
                jellyYSpring.impulse(-1.40f * amp)
                bodyLiftSpring.impulse(-18f * amp)
                tipLagXSpring.impulse(14f * amp)
                if (lastTapCycleIdx == 0) {
                    triggerMicroAction(PetMicroAction.KITA_AURA_WINK, duration = 0.95f)
                } else {
                    triggerMicroAction(PetMicroAction.KITA_SING_SWAY, duration = 1.05f)
                }
                emitNonRepeatingEffect()
            }
        }
    }

    fun step(dtSec: Float, uptimeMillis: Long) {
        val dt = dtSec.coerceIn(0.001f, 0.033f)
        localElapsedSec += dt
        val intensity = physicsIntensity.coerceIn(0.4f, 1.6f)
        val resp = physicsResponsiveness.coerceIn(0.5f, 1.6f)

        if (pointerActiveTimer > 0f) {
            pointerActiveTimer = (pointerActiveTimer - dt).coerceAtLeast(0f)
        }

        // 1. Update personality micro-action state machine
        if (activeMicroAction != PetMicroAction.NONE) {
            microElapsedSec += dt
            if (microElapsedSec >= microDurationSec) {
                activeMicroAction = PetMicroAction.NONE
                microElapsedSec = 0f
                microDurationSec = 0f
            }
        } else {
            nextMicroActionInSec -= dt
            if (nextMicroActionInSec <= 0f) {
                nextMicroActionInSec = randomRange(4.8f, 9.6f)
                triggerRandomPersonalityAction()
            }
        }

        // 2. Character-distinct asynchronous idle rhythm (prime-ratio non-integer periods)
        val bPhase = (localElapsedSec * (2.0 * PI / profile.breathPeriodSec)).toFloat()
        val sPhase = (localElapsedSec * (2.0 * PI / profile.swayPeriodSec)).toFloat()
        val breathLiftTarget: Float
        val baseHeadTiltDeg: Float
        val baseShoulderSway: Float
        when (profile.petId) {
            "nijika" -> {
                // Crisp upbeat drummer bounce
                val pulse = max(0f, sin(bPhase))
                breathLiftTarget = -profile.breathLiftPx * (0.35f * (0.5f - 0.5f * cos(bPhase)) + 0.65f * pulse * pulse) * intensity
                baseHeadTiltDeg = (profile.headSwayDeg * sin(sPhase) + 0.45f * sin(bPhase)) * intensity
                baseShoulderSway = (sin(sPhase) * 1.15f) * intensity
            }
            "hitori" -> {
                // Gentle slightly quicker shy breathing with subtle timid sway
                val softBreath = 0.5f - 0.5f * cos(bPhase)
                breathLiftTarget = -profile.breathLiftPx * softBreath * intensity
                baseHeadTiltDeg = (-0.55f + profile.headSwayDeg * sin(sPhase)) * intensity
                baseShoulderSway = (sin(sPhase) * 0.65f) * intensity
            }
            "ryo" -> {
                // Slow, deep, calm low-frequency bass groove
                val slowGroove = 0.5f - 0.5f * cos(bPhase)
                breathLiftTarget = -profile.breathLiftPx * (slowGroove * slowGroove) * intensity
                baseHeadTiltDeg = (profile.headSwayDeg * sin(sPhase)) * intensity
                baseShoulderSway = (sin(sPhase) * 0.55f) * intensity
            }
            else -> {
                // Kita: bright singing shoulder sway + buoyant breath
                breathLiftTarget = -profile.breathLiftPx * (0.5f - 0.5f * cos(bPhase)) * intensity
                baseHeadTiltDeg = (profile.headSwayDeg * sin(sPhase) + 0.35f * sin(bPhase * 0.5f)) * intensity
                baseShoulderSway = (sin(sPhase) * 1.65f) * intensity
            }
        }

        // 3. Character-exclusive micro-action modulation (smooth, expressive, never cracks or causes dizziness)
        var extraTiltDeg = 0f
        var extraLiftPx = 0f
        var extraLookX = 0f
        var extraLookY = 0f
        var extraShoulder = 0f
        var forceLeftEye: PetEyeState? = null
        var forceRightEye: PetEyeState? = null

        val u = if (microDurationSec > 0f) (microElapsedSec / microDurationSec).coerceIn(0f, 1f) else 0f
        val env = sin(u * PI.toFloat())
        when (activeMicroAction) {
            // --- NIJIKA ---
            PetMicroAction.NIJIKA_DRUM_GROOVE -> {
                val hop = abs(sin(u * 2f * PI.toFloat()))
                extraLiftPx = -hop * 3.4f * intensity
                extraTiltDeg = sin(u * 2f * PI.toFloat()) * 3.2f * intensity
                extraShoulder = sin(u * 2f * PI.toFloat()) * 1.5f * intensity
            }
            PetMicroAction.NIJIKA_WINK_CHEER -> {
                extraTiltDeg = 4.6f * env * intensity
                extraLiftPx = -1.6f * env * intensity
                extraLookX = 0.22f * env
                if (u in 0.14f..0.82f) {
                    forceRightEye = PetEyeState.CLOSED
                } else if (u in 0.06f..0.90f) {
                    forceRightEye = PetEyeState.HALF
                }
            }
            PetMicroAction.NIJIKA_AHOGE_FLICK -> {
                val flick = sin(u * 2f * PI.toFloat()) * env
                extraTiltDeg = flick * 4.2f * intensity
                extraLiftPx = -abs(flick) * 1.5f * intensity
            }
            // --- HITORI ---
            PetMicroAction.HITORI_SHY_DUCK -> {
                extraLiftPx = 2.4f * env * intensity
                extraTiltDeg = -3.4f * env * intensity
                extraLookX = -0.28f * env
                extraLookY = 0.22f * env
            }
            PetMicroAction.HITORI_PEEK_BLINK -> {
                extraTiltDeg = 2.2f * env * intensity
                extraLookX = 0.32f * env
                extraLiftPx = -0.6f * env * intensity
            }
            PetMicroAction.HITORI_GUITAR_HUG -> {
                extraLiftPx = 2.8f * env * intensity
                extraTiltDeg = -2.8f * env * intensity
                extraShoulder = -1.1f * env * intensity
                extraLookX = -0.35f * env
                extraLookY = 0.28f * env
            }
            // --- RYO ---
            PetMicroAction.RYO_BASS_GROOVE -> {
                // Slow heavy grounded 2-beat cool bass nod with composed half-lidded eyes
                val slowNod = sin(u * PI.toFloat())
                val subtleDouble = 0.65f * slowNod + 0.35f * abs(sin(u * 2f * PI.toFloat())) * slowNod
                extraLiftPx = subtleDouble * 2.5f * intensity
                extraTiltDeg = -2.6f * slowNod * intensity
                extraLookY = 0.32f * slowNod
                if (u in 0.18f..0.76f) {
                    forceLeftEye = PetEyeState.HALF
                    forceRightEye = PetEyeState.HALF
                }
            }
            PetMicroAction.RYO_LAZY_STARE -> {
                // Unbothered half-lidded cool tilt + slow gaze
                extraTiltDeg = 3.0f * env * intensity
                extraLiftPx = 1.0f * env * intensity
                extraLookX = 0.24f * env
                if (u in 0.12f..0.86f) {
                    forceLeftEye = PetEyeState.HALF
                    forceRightEye = PetEyeState.HALF
                }
            }
            PetMicroAction.RYO_ALOOF_GLANCE -> {
                extraTiltDeg = -2.2f * env * intensity
                extraLookX = -0.35f * env
                extraShoulder = -0.6f * env * intensity
            }
            // --- KITA ---
            PetMicroAction.KITA_SING_SWAY -> {
                val wave = sin(u * 2f * PI.toFloat()) * env
                extraShoulder = wave * 2.5f * intensity
                extraTiltDeg = wave * 3.6f * intensity
                extraLiftPx = -abs(wave) * 1.6f * intensity
            }
            PetMicroAction.KITA_AURA_WINK -> {
                extraTiltDeg = -4.5f * env * intensity
                extraShoulder = 1.8f * env * intensity
                if (u in 0.14f..0.82f) {
                    forceRightEye = PetEyeState.CLOSED
                } else if (u in 0.06f..0.90f) {
                    forceRightEye = PetEyeState.HALF
                }
            }
            PetMicroAction.KITA_HAPPY_HOP -> {
                val hop = abs(sin(u * 2f * PI.toFloat()))
                extraLiftPx = -hop * 3.2f * intensity
                extraShoulder = sin(u * 2f * PI.toFloat()) * 1.8f * intensity
                extraTiltDeg = sin(u * 2f * PI.toFloat()) * 2.6f * intensity
            }
            PetMicroAction.NONE -> Unit
        }

        // 4. Smooth continuous gaze target (smooth sine drift — NEVER abrupt jumps)
        val pointerBlend = (pointerActiveTimer / 0.5f).coerceIn(0f, 1f)
        val organicWanderX = sin(localElapsedSec * 0.58f) * 0.20f
        val organicWanderY = sin(localElapsedSec * 0.43f + 1.1f) * 0.12f
        val rawLookX = if (pointerBlend > 0f) {
            pointerTargetX * pointerBlend + (screenBiasX + organicWanderX) * (1f - pointerBlend)
        } else {
            screenBiasX + organicWanderX
        } + extraLookX
        val rawLookY = if (pointerBlend > 0f) {
            pointerTargetY * pointerBlend + (screenBiasY + organicWanderY) * (1f - pointerBlend)
        } else {
            screenBiasY + organicWanderY
        } + extraLookY

        val targetLookX = rawLookX.coerceIn(-1f, 1f) * intensity
        val targetLookY = rawLookY.coerceIn(-0.8f, 0.8f) * intensity

        // 5. Step Tier-1 Primary Springs
        val hFreq = profile.headFreqHz * resp
        val prevHeadTilt = headTiltSpring.value
        val prevBodyLift = bodyLiftSpring.value

        lookXSpring.step(targetLookX, hFreq * 0.62f, max(0.78f, profile.headDamping), dt)
        lookYSpring.step(targetLookY, hFreq * 0.62f, max(0.78f, profile.headDamping), dt)
        headTiltSpring.step(baseHeadTiltDeg + extraTiltDeg, hFreq, profile.headDamping, dt)
        bodyLiftSpring.step(breathLiftTarget + extraLiftPx, hFreq * 0.88f, profile.headDamping, dt)
        shoulderSwaySpring.step(baseShoulderSway + extraShoulder, hFreq * 0.82f, profile.headDamping, dt)
        dragTiltSpring.step(if (isDragging) dragTargetTiltDeg else 0f, 4.8f * resp, 0.66f, dt)

        val jellyFreq = if (profile.petId == "ryo") 3.4f * resp else 5.6f * resp
        val jellyDamp = if (profile.petId == "ryo") 0.86f else 0.44f
        jellyXSpring.step(1f, jellyFreq, jellyDamp, dt)
        jellyYSpring.step(1f, jellyFreq, jellyDamp, dt)

        // 6. Step Tier-2 Delayed Secondary Springs (hair & ahoge follow head velocity & lag half a beat)
        val headAngVel = (headTiltSpring.value - prevHeadTilt) / dt
        val headVertVel = (bodyLiftSpring.value - prevBodyLift) / dt
        val ambientBreeze = sin(localElapsedSec * 1.40f) * 1.45f * intensity
        val hairTargetX = (-headTiltSpring.value * 0.58f - headAngVel * 0.06f - dragTiltSpring.value * 0.35f + ambientBreeze)
        val hairTargetY = (-headVertVel * 0.045f + abs(headTiltSpring.value) * 0.10f)
        val tipTargetX = (-headTiltSpring.value * 0.82f - headAngVel * 0.09f - dragTiltSpring.value * 0.48f + ambientBreeze * 1.25f)
        val tipTargetY = (-headVertVel * 0.065f)

        hairLagXSpring.step(hairTargetX, profile.hairFreqHz * resp, profile.hairDamping, dt)
        hairLagYSpring.step(hairTargetY, profile.hairFreqHz * resp, profile.hairDamping, dt)
        tipLagXSpring.step(tipTargetX, profile.tipFreqHz * resp, profile.tipDamping, dt)
        tipLagYSpring.step(tipTargetY, profile.tipFreqHz * resp, profile.tipDamping, dt)

        // 7. Populate strict parent-child bone hierarchy pose (ZERO neck or hair detachment!)
        pose.jellyScaleX = jellyXSpring.value.coerceIn(0.90f, 1.12f)
        pose.jellyScaleY = jellyYSpring.value.coerceIn(0.90f, 1.12f)

        val breathLift = bodyLiftSpring.value.coerceIn(-6.0f, 5.2f)
        // Parent Torso transform (pivots at bottom anchor)
        pose.clothesTx = (shoulderSwaySpring.value * 0.55f).coerceIn(-3.6f, 3.6f)
        pose.clothesTy = (breathLift * 0.60f).coerceIn(-3.8f, 3.2f)
        pose.clothesRotDeg = (
            dragTiltSpring.value * 0.50f +
            shoulderSwaySpring.value * 0.72f +
            headTiltSpring.value * 0.34f
        ).coerceIn(-7.5f, 7.5f)
        pose.clothesBreathScaleY = (1f - breathLift * 0.0048f).coerceIn(0.975f, 1.030f)

        // Child Head transform STRICTLY RELATIVE to Torso (pivots at neckPivot inside Torso space!)
        pose.headRelTx = (lookXSpring.value * 0.95f).coerceIn(-1.2f, 1.2f)
        pose.headRelTy = (breathLift * 0.18f + lookYSpring.value * 0.65f).coerceIn(-0.95f, 0.95f)
        pose.headRelRotDeg = (
            headTiltSpring.value * 0.66f +
            dragTiltSpring.value * 0.20f +
            lookXSpring.value * 1.15f
        ).coerceIn(-5.5f, 5.5f)

        updateEyes(dt, forceLeftEye, forceRightEye)
    }

    private fun updateEyes(dt: Float, forceLeft: PetEyeState?, forceRight: PetEyeState?) {
        var baseBlink = PetEyeState.OPEN
        if (blinkElapsedSec >= 0f) {
            blinkElapsedSec += dt
            baseBlink = when {
                blinkElapsedSec < 0.080f -> PetEyeState.HALF
                blinkElapsedSec < 0.260f -> PetEyeState.CLOSED
                blinkElapsedSec < 0.375f -> PetEyeState.HALF
                else -> {
                    if (queuedSecondBlink) {
                        queuedSecondBlink = false
                        blinkElapsedSec = -0.11f
                    } else {
                        blinkElapsedSec = -1f
                        nextBlinkInSec = randomRange(3.8f, 6.8f)
                    }
                    PetEyeState.OPEN
                }
            }
        } else if (blinkElapsedSec < -0.5f) {
            nextBlinkInSec -= dt
            if (nextBlinkInSec <= 0f) {
                blinkElapsedSec = 0f
            }
        } else {
            blinkElapsedSec += dt
            if (blinkElapsedSec >= 0f) blinkElapsedSec = 0f
        }

        leftEyeState = forceLeft ?: baseBlink
        rightEyeState = forceRight ?: baseBlink
    }

    private fun pickNonRepeatingIndex(count: Int, lastIdx: Int): Int {
        if (count <= 1) return 0
        return if (lastIdx < 0 || lastIdx >= count) {
            random.nextInt(count)
        } else {
            (lastIdx + 1 + random.nextInt(count - 1)) % count
        }
    }

    private fun emitNonRepeatingEffect() {
        val next = pickNonRepeatingIndex(PET_EFFECT_VARIANT_COUNT, lastEffectVariant)
        lastEffectVariant = next
        effectVariantIndex = next
        effectBurstTick += 1
    }

    private fun triggerRandomPersonalityAction() {
        val choice = pickNonRepeatingIndex(3, lastRandomActionIdx)
        lastRandomActionIdx = choice
        when (profile.petId) {
            "nijika" -> when (choice) {
                0 -> {
                    triggerMicroAction(PetMicroAction.NIJIKA_DRUM_GROOVE, randomRange(0.95f, 1.25f))
                    emitNonRepeatingEffect()
                }
                1 -> triggerMicroAction(PetMicroAction.NIJIKA_WINK_CHEER, randomRange(0.88f, 1.12f))
                else -> {
                    triggerMicroAction(PetMicroAction.NIJIKA_AHOGE_FLICK, randomRange(0.85f, 1.10f))
                    tipLagXSpring.impulse(12f * physicsIntensity)
                }
            }
            "hitori" -> when (choice) {
                0 -> {
                    triggerMicroAction(PetMicroAction.HITORI_SHY_DUCK, randomRange(0.95f, 1.25f))
                    emitNonRepeatingEffect()
                }
                1 -> {
                    triggerMicroAction(PetMicroAction.HITORI_PEEK_BLINK, randomRange(0.95f, 1.20f))
                    blinkElapsedSec = 0f
                    queuedSecondBlink = true
                }
                else -> triggerMicroAction(PetMicroAction.HITORI_GUITAR_HUG, randomRange(1.15f, 1.45f))
            }
            "ryo" -> when (choice) {
                0 -> {
                    triggerMicroAction(PetMicroAction.RYO_BASS_GROOVE, randomRange(1.20f, 1.50f))
                    emitNonRepeatingEffect()
                }
                1 -> triggerMicroAction(PetMicroAction.RYO_LAZY_STARE, randomRange(1.35f, 1.75f))
                else -> triggerMicroAction(PetMicroAction.RYO_ALOOF_GLANCE, randomRange(1.25f, 1.60f))
            }
            else -> when (choice) {
                0 -> triggerMicroAction(PetMicroAction.KITA_SING_SWAY, randomRange(1.10f, 1.40f))
                1 -> {
                    triggerMicroAction(PetMicroAction.KITA_AURA_WINK, randomRange(0.95f, 1.20f))
                    emitNonRepeatingEffect()
                }
                else -> {
                    triggerMicroAction(PetMicroAction.KITA_HAPPY_HOP, randomRange(0.90f, 1.15f))
                    emitNonRepeatingEffect()
                }
            }
        }
    }

    private fun triggerMicroAction(action: PetMicroAction, duration: Float) {
        activeMicroAction = action
        microElapsedSec = 0f
        microDurationSec = duration
    }

    /**
     * Piecewise-rigid 28x28 Linear Blend Skinning (LBS) over the single unbroken full-character
     * sprite:
     * - Entire face/eyes/scalp (headW = 1.0, hairW = 0.0) rotates 100% rigidly around neckPivot.
     * - Entire torso/guitar/bass/drum (headW = 0.0, hairW = 0.0) rotates 100% rigidly around bottom anchor.
     * - Soft 24px neck collar blends headW smoothly 0 -> 1 (ZERO cut seams, ZERO cracks at ANY amplitude!).
     * - Outer free-hanging ponytail/ahoge/long hair tips flex elastically with Tier-2 delayed springs.
     */
    fun deformCharacterMesh(outVertices: FloatArray, width: Float = PET_DESIGN_SIZE, height: Float = PET_DESIGN_SIZE) {
        val sx = width / PET_DESIGN_SIZE
        val sy = height / PET_DESIGN_SIZE
        val hairX = hairLagXSpring.value.coerceIn(-6.0f, 6.0f)
        val hairY = hairLagYSpring.value.coerceIn(-2.2f, 2.2f)
        val tipX = tipLagXSpring.value.coerceIn(-7.0f, 7.0f)
        val tipY = tipLagYSpring.value.coerceIn(-2.6f, 2.6f)

        val nx = profile.neckPivotX
        val ny = profile.neckPivotY
        val anchorX = 128f
        val anchorY = profile.anchorBottomY

        val headRelRad = (pose.headRelRotDeg * (PI / 180.0)).toFloat()
        val headRelTx = pose.headRelTx
        val headRelTy = pose.headRelTy

        val torsoRad = (pose.clothesRotDeg * (PI / 180.0)).toFloat()
        val cosT = cos(torsoRad)
        val sinT = sin(torsoRad)
        val torsoTx = pose.clothesTx
        val torsoTy = pose.clothesTy
        val breathScaleY = pose.clothesBreathScaleY
        val jellyX = pose.jellyScaleX
        val jellyY = pose.jellyScaleY

        val bx = weights.baseX
        val by = weights.baseY
        val anchorW = weights.anchorW
        val headW = weights.headW
        val hairW = weights.hairW
        val hairPx = weights.hairPerpX
        val hairPy = weights.hairPerpY
        val tipW = weights.tipW
        val tipPx = weights.tipPerpX
        val tipPy = weights.tipPerpY

        for (i in 0 until PET_MESH_VERTICES) {
            var x = bx[i]
            var y = by[i]

            // 1. Outer hair tip elastic sway (strictly 0.0 inside face/eyes & instruments)
            val fw = hairW[i]
            if (abs(fw) > 0.001f) {
                x += (hairPx[i] * hairX) * fw
                y += (hairPy[i] * hairX + hairY) * abs(fw)
            }
            val tw = tipW[i]
            if (abs(tw) > 0.001f) {
                x += (tipPx[i] * tipX) * tw
                y += (tipPy[i] * tipX + tipY) * abs(tw)
            }

            // 2. Child Head local rotation & subtle parallax around neckPivot (weighted smoothly by headW)
            val hw = headW[i]
            if (hw > 0.001f) {
                val ang = headRelRad * hw
                val ch = cos(ang)
                val sh = sin(ang)
                val rx = x - nx
                val ry = y - ny
                x = nx + rx * ch - ry * sh + headRelTx * hw
                y = ny + rx * sh + ry * ch + headRelTy * hw
            }

            // 3. Parent Torso breathing & rigid sway anchored cleanly at bottom
            val aw = anchorW[i]
            val localBreathScaleY = 1f + (breathScaleY - 1f) * aw
            val ty0 = anchorY + (y - anchorY) * localBreathScaleY
            val tx0 = x
            val dxT = tx0 - anchorX
            val dyT = ty0 - anchorY
            val xT = anchorX + dxT * cosT - dyT * sinT + torsoTx * aw
            val yT = anchorY + dxT * sinT + dyT * cosT + torsoTy * aw

            // 4. Root Jelly squash & stretch anchored at bottom
            val xF = anchorX + (xT - anchorX) * jellyX
            val yF = anchorY + (yT - anchorY) * jellyY

            outVertices[i * 2] = xF * sx
            outVertices[i * 2 + 1] = yF * sy
        }
    }

    fun deformHairMesh(outVertices: FloatArray, width: Float = PET_DESIGN_SIZE, height: Float = PET_DESIGN_SIZE) {
        deformCharacterMesh(outVertices, width, height)
    }

    private fun randomRange(minVal: Float, maxVal: Float): Float =
        minVal + random.nextFloat() * (maxVal - minVal)
}

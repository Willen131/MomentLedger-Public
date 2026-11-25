package com.momentledger.app.quickentry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetRigTest {
    @Test
    fun blinkClosureFollowsTheAuthoredCurve() {
        assertEquals(820L, PET_BLINK_DURATION_MILLIS)

        assertEquals(0f, petBlinkClosure(0f), 0.001f)
        assertEquals(0f, petBlinkClosure(1f), 0.001f)
        assertEquals(0.5f, petBlinkClosure(0.13f), 0.001f)

        // The eyelids stay fully closed across the authored plateau.
        assertEquals(1f, petBlinkClosure(0.26f), 0.001f)
        assertEquals(1f, petBlinkClosure(0.44f), 0.001f)
        assertEquals(1f, petBlinkClosure(0.62f), 0.001f)

        assertEquals(0.5f, petBlinkClosure(0.81f), 0.001f)
    }

    @Test
    fun breathLiftHasAVisiblePeakWithinTheAuthoredRange() {
        assertEquals(0f, petBreathLift(0L), 0.001f)

        var maxLift = 0f
        var elapsedMillis = 0L
        while (elapsedMillis <= 15_000L) {
            val lift = petBreathLift(elapsedMillis)
            assertTrue(
                "Breath lift at ${elapsedMillis}ms was $lift",
                lift in 0f..1.45f
            )
            maxLift = maxOf(maxLift, lift)
            elapsedMillis += 50L
        }

        assertTrue("Expected a clear breath peak, max was $maxLift", maxLift > 1.3f)
    }

    @Test
    fun hairTipOffsetSwaysWithinItsPixelBudgetForBothPhases() {
        val lags = longArrayOf(0L, 370L)
        var minOffset = Float.MAX_VALUE
        var maxOffset = -Float.MAX_VALUE
        var hasPositiveOffset = false
        var hasNegativeOffset = false
        var lagPhaseDiffersFromLead = false

        for (lagMillis in lags) {
            var elapsedMillis = 0L
            while (elapsedMillis <= 18_000L) {
                val offset = petHairTipOffset(elapsedMillis, lagMillis)
                assertTrue(
                    "Hair tip offset at ${elapsedMillis}ms (lag ${lagMillis}ms) exceeded 5.5px: $offset",
                    abs(offset) <= 5.5f
                )
                hasPositiveOffset = hasPositiveOffset || offset > 0f
                hasNegativeOffset = hasNegativeOffset || offset < 0f
                minOffset = minOf(minOffset, offset)
                maxOffset = maxOf(maxOffset, offset)

                if (lagMillis != 0L &&
                    abs(offset - petHairTipOffset(elapsedMillis, 0L)) > 0.001f
                ) {
                    lagPhaseDiffersFromLead = true
                }

                elapsedMillis += 50L
            }
        }

        assertTrue("Hair tip offset never became positive", hasPositiveOffset)
        assertTrue("Hair tip offset never became negative", hasNegativeOffset)
        assertTrue(
            "Expected peak-to-peak sway over 6px, was ${maxOffset - minOffset}",
            maxOffset - minOffset > 6f
        )
        assertTrue("Lag phase never diverged from the lead phase", lagPhaseDiffersFromLead)
    }

    @Test
    fun hairInfluenceRampsOnlyAcrossTheAuthoredWindow() {
        assertEquals(0f, petHairInfluence(-0.2f), 0.001f)
        assertEquals(0f, petHairInfluence(0f), 0.001f)
        assertEquals(0f, petHairInfluence(0.12f), 0.001f)
        assertEquals(0.5f, petHairInfluence(0.53f), 0.04f)
        assertEquals(1f, petHairInfluence(1f), 0.001f)
        assertEquals(1f, petHairInfluence(1.2f), 0.001f)
    }

    @Test
    fun hairCoverageKeepsTheRootAndClearsOnlyTheFreeLength() {
        assertEquals(0f, petHairCoverage(0f), 0.001f)
        assertEquals(0f, petHairCoverage(0.22f), 0.001f)
        assertEquals(0.5f, petHairCoverage(0.4f), 0.001f)
        assertEquals(1f, petHairCoverage(0.58f), 0.001f)
        assertEquals(1f, petHairCoverage(0.8f), 0.001f)
    }

    @Test
    fun dockedMotionSwitchControlsAnimationOnlyWhenDockedAndMotionEnabled() {
        assertTrue(shouldPetAnimate(visibleToUser = true, docked = false, motion = true, dockedMotion = false, animatorsEnabled = true))
        assertEquals(false, shouldPetAnimate(visibleToUser = true, docked = true, motion = true, dockedMotion = false, animatorsEnabled = true))
        assertTrue(shouldPetAnimate(visibleToUser = true, docked = true, motion = true, dockedMotion = true, animatorsEnabled = true))
        assertEquals(false, shouldPetAnimate(visibleToUser = true, docked = true, motion = false, dockedMotion = true, animatorsEnabled = true))
        assertEquals(false, shouldPetAnimate(visibleToUser = false, docked = true, motion = true, dockedMotion = true, animatorsEnabled = true))
        assertEquals(false, shouldPetAnimate(visibleToUser = true, docked = true, motion = true, dockedMotion = true, animatorsEnabled = false))
    }

    @Test
    fun secondOrderSpringShowsElasticOvershootAndSettlesCleanly() {
        val spring = Spring1D(0f, 0f)
        var peak = 0f
        repeat(120) {
            val v = spring.step(target = 1f, freqHz = 4.5f, dampingRatio = 0.42f, dtSec = 1f / 60f)
            if (v > peak) peak = v
        }
        assertTrue("Underdamped spring should overshoot target (peak=$peak)", peak > 1.15f)
        assertEquals(1f, spring.value, 0.02f)
    }

    @Test
    fun meshWeightsAnchorBottomPreserve3DFaceParallaxAndFlexHairWithoutSeams() {
        val profiles = listOf(
            PetPhysicsProfiles.HITORI,
            PetPhysicsProfiles.RYO,
            PetPhysicsProfiles.NIJIKA,
            PetPhysicsProfiles.KITA
        )
        for (profile in profiles) {
            val weights = PetMeshWeights(profile)
            // Bottom row must be fully anchored (0.0) so instruments & body bottom never detach
            for (col in 0..PET_MESH_GRID) {
                val bottomIdx = PET_MESH_GRID * (PET_MESH_GRID + 1) + col
                assertEquals("${profile.petId} bottom col $col should be anchored", 0f, weights.anchorW[bottomIdx], 0.001f)
            }
            // Face center vertex must have much higher pseudo-3D weight than outer edge vertices
            // AND strictly 0.0 hair/tip flex weight so unified Head+Hair mesh never warps facial features!
            val faceCenterIdx = nearestVertex(weights, profile.faceCx, profile.faceCy)
            val outerEdgeIdx = nearestVertex(weights, 12f, profile.faceCy)
            assertTrue(
                "${profile.petId} face3D center (${weights.face3dW[faceCenterIdx]}) should exceed outer contour (${weights.face3dW[outerEdgeIdx]})",
                weights.face3dW[faceCenterIdx] > 0.85f && weights.face3dW[faceCenterIdx] > weights.face3dW[outerEdgeIdx] + 0.6f
            )
            assertEquals("${profile.petId} face center must have 0 hair flex", 0f, weights.hairW[faceCenterIdx], 0.001f)
            assertEquals("${profile.petId} face center must have 0 tip flex", 0f, weights.tipW[faceCenterIdx], 0.001f)
            assertEquals("${profile.petId} face center must have full rigid headW=1", 1f, weights.headW[faceCenterIdx], 0.001f)
            // Instrument exclusion zones (guitars/bass/drumsticks/mic) must stay 100% locked to rigid Torso
            for (ex in profile.headExclusions) {
                val exIdx = nearestVertex(weights, ex.cx, ex.cy)
                assertEquals("${profile.petId} exclusion (${ex.cx},${ex.cy}) must stay locked to torso", 0f, weights.headW[exIdx], 0.001f)
            }
            // Hair flex zones must have active tip weights (except Ryo whose sleek short bob is unified on head)
            val maxHairFlex = (0 until PET_MESH_VERTICES).maxOf { maxOf(abs(weights.hairW[it]), abs(weights.tipW[it])) }
            if (profile.hairZones.isEmpty()) {
                assertEquals("${profile.petId} unified bob should have 0 mesh split weight", 0f, maxHairFlex, 0.001f)
            } else {
                assertTrue("${profile.petId} expected active hair flex weight, got $maxHairFlex", maxHairFlex > 0.5f)
                // Every hair zone's root vertex must be strictly pinned at 0.0 so hair never detaches from scalp
                for (zone in profile.hairZones) {
                    val rootIdx = nearestVertex(weights, zone.rootX, zone.rootY)
                    assertEquals("${profile.petId} hair root must stay pinned", 0f, weights.hairW[rootIdx], 0.001f)
                    assertEquals("${profile.petId} tip root must stay pinned", 0f, weights.tipW[rootIdx], 0.001f)
                }
            }
        }
    }

    @Test
    fun charactersUseDistinctPersonalityActionsParentChildBoundsAndNonRepeatingEffects() {
        val periods = listOf(
            PetPhysicsProfiles.HITORI.breathPeriodSec,
            PetPhysicsProfiles.RYO.breathPeriodSec,
            PetPhysicsProfiles.NIJIKA.breathPeriodSec,
            PetPhysicsProfiles.KITA.breathPeriodSec
        )
        // All 4 breath periods are distinct and Hitori breathes faster than Ryo
        assertEquals(4, periods.toSet().size)
        assertTrue(PetPhysicsProfiles.HITORI.breathPeriodSec < PetPhysicsProfiles.RYO.breathPeriodSec)
        assertTrue(PetPhysicsProfiles.RYO.headDamping > PetPhysicsProfiles.NIJIKA.headDamping)

        // All 4 characters have strictly distinct tap actions (zero shared copy-paste motions)
        val expectedTapPools = mapOf(
            PetPhysicsProfiles.NIJIKA to setOf(PetMicroAction.NIJIKA_WINK_CHEER, PetMicroAction.NIJIKA_DRUM_GROOVE),
            PetPhysicsProfiles.HITORI to setOf(PetMicroAction.HITORI_SHY_DUCK, PetMicroAction.HITORI_GUITAR_HUG),
            PetPhysicsProfiles.RYO to setOf(PetMicroAction.RYO_LAZY_STARE, PetMicroAction.RYO_BASS_GROOVE),
            PetPhysicsProfiles.KITA to setOf(PetMicroAction.KITA_AURA_WINK, PetMicroAction.KITA_SING_SWAY)
        )
        assertEquals(8, expectedTapPools.values.flatten().toSet().size)

        val verts = FloatArray(PET_MESH_VERTICES * 2)
        for ((profile, expectedActions) in expectedTapPools) {
            val ctrl = PetLive2dController(profile, kotlin.random.Random(7))
            var prevVariant = -1
            val seenVariants = mutableSetOf<Int>()
            val seenActions = mutableSetOf<PetMicroAction>()
            var maxJellyDelta = 0f

            repeat(12) { tapIdx ->
                val beforeFx = ctrl.effectBurstTick
                ctrl.onTapJelly()
                assertTrue("${profile.petId} should emit signature effect on tap", ctrl.effectBurstTick > beforeFx)
                if (prevVariant >= 0) {
                    assertTrue(
                        "${profile.petId} tap $tapIdx repeated effect variant $prevVariant",
                        ctrl.effectVariantIndex != prevVariant
                    )
                }
                prevVariant = ctrl.effectVariantIndex
                seenVariants += prevVariant
                seenActions += ctrl.activeMicroAction

                repeat(6) { f ->
                    ctrl.step(0.016f, 1000L + (tapIdx * 100L) + f * 16L)
                    maxJellyDelta = maxOf(maxJellyDelta, abs(ctrl.pose.jellyScaleX - 1f))
                }
                ctrl.deformHairMesh(verts)
                assertTrue(verts.all { it.isFinite() })
                assertTrue(
                    "Parent-child headRelTx must stay tightly bound to prevent neck cracks",
                    abs(ctrl.pose.headRelTx) <= 1.25f && abs(ctrl.pose.headRelTy) <= 1.0f
                )
                assertTrue(
                    "Parent-child headRelRotDeg must stay tightly bound",
                    abs(ctrl.pose.headRelRotDeg) <= 5.6f
                )
            }

            assertEquals("${profile.petId} should alternate its own tap actions", expectedActions, seenActions)
            assertTrue("${profile.petId} should cycle across multiple effect variants", seenVariants.size >= 2)
            if (profile.petId == "ryo") {
                assertTrue("Ryo tap should stay calm & high-damping (maxJellyDelta=$maxJellyDelta)", maxJellyDelta < 0.05f)
            }

            // Verify extreme 160% intensity + drag inertia produces finite, crack-free LBS mesh
            ctrl.physicsIntensity = 1.6f
            ctrl.onDragVelocity(650f, -150f)
            repeat(12) { ctrl.step(0.016f, 5000L + it * 16L) }
            ctrl.deformCharacterMesh(verts)
            assertTrue(verts.all { it.isFinite() })
            assertTrue(abs(ctrl.pose.headRelRotDeg) <= 5.6f)
        }
    }

    private fun nearestVertex(weights: PetMeshWeights, targetX: Float, targetY: Float): Int {
        var bestIdx = 0
        var bestDistSq = Float.MAX_VALUE
        for (i in 0 until PET_MESH_VERTICES) {
            val dx = weights.baseX[i] - targetX
            val dy = weights.baseY[i] - targetY
            val d2 = dx * dx + dy * dy
            if (d2 < bestDistSq) {
                bestDistSq = d2
                bestIdx = i
            }
        }
        return bestIdx
    }
}

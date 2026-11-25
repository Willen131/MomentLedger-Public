package com.momentledger.app.quickentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenReviewHandoffTest {

    private class FakeClock(var nowMillis: Long = 1_000L) {
        fun advanceBy(millis: Long) {
            nowMillis += millis
        }
    }

    @Test
    fun unknownTokenDoesNotConsumePublishedValue() {
        val clock = FakeClock()
        val handoff = ScreenReviewHandoff<String>(nowMillis = { clock.nowMillis })

        val token = handoff.publish("captured screen")

        assertNull(handoff.take("not-the-issued-token"))
        assertEquals("captured screen", handoff.take(token))
    }

    @Test
    fun validTokenCanOnlyBeConsumedOnce() {
        val clock = FakeClock()
        val handoff = ScreenReviewHandoff<String>(nowMillis = { clock.nowMillis })

        val token = handoff.publish("captured screen")

        assertEquals("captured screen", handoff.take(token))
        assertNull(handoff.take(token))
    }

    @Test
    fun tokenExpiresAfterTtl() {
        val clock = FakeClock()
        val handoff = ScreenReviewHandoff<String>(
            nowMillis = { clock.nowMillis },
            ttlMillis = 20_000L
        )

        val token = handoff.publish("captured screen")
        clock.advanceBy(20_001L)

        assertNull(handoff.take(token))
    }

    @Test
    fun discardingStaleTokenLeavesLatestValueIntact() {
        val clock = FakeClock()
        val handoff = ScreenReviewHandoff<String>(nowMillis = { clock.nowMillis })

        val staleToken = handoff.publish("first screen")
        val latestToken = handoff.publish("second screen")

        handoff.discard(staleToken)

        assertEquals("second screen", handoff.take(latestToken))
    }

    @Test
    fun extendTtlKeepsPendingTokenAliveForNotificationFallback() {
        val clock = FakeClock()
        val handoff = ScreenReviewHandoff<String>(
            nowMillis = { clock.nowMillis },
            ttlMillis = 20_000L
        )

        val token = handoff.publish("captured screen")
        clock.advanceBy(1_200L)
        org.junit.Assert.assertTrue(handoff.isPending(token))
        org.junit.Assert.assertTrue(handoff.extendTtl(token, 120_000L))

        clock.advanceBy(60_000L)
        org.junit.Assert.assertTrue(handoff.isPending(token))
        assertEquals("captured screen", handoff.take(token))
        org.junit.Assert.assertFalse(handoff.isPending(token))
    }
}

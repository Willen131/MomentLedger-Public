package com.momentledger.app.quickentry

import java.util.UUID

/** One pending review keyed by a random, single-use token. Nothing is put in the launch intent. */
internal class ScreenReviewHandoff<T : Any>(
    private val nowMillis: () -> Long,
    private val ttlMillis: Long = TTL_MILLIS
) {
    private data class Pending<T>(val token: String, val value: T, val expiresAt: Long)

    private var pending: Pending<T>? = null

    @Synchronized
    fun publish(value: T): String {
        val token = UUID.randomUUID().toString()
        pending = Pending(token, value, nowMillis() + ttlMillis)
        return token
    }

    @Synchronized
    fun take(token: String): T? {
        val entry = pending ?: return null
        if (entry.expiresAt <= nowMillis()) {
            pending = null
            return null
        }
        if (entry.token != token) return null
        pending = null
        return entry.value
    }

    @Synchronized
    fun isPending(token: String): Boolean {
        val entry = pending ?: return false
        if (entry.expiresAt <= nowMillis()) {
            pending = null
            return false
        }
        return entry.token == token
    }

    @Synchronized
    fun extendTtl(token: String, totalMillisFromNow: Long): Boolean {
        val entry = pending ?: return false
        if (entry.token != token) return false
        val now = nowMillis()
        if (entry.expiresAt <= now) {
            pending = null
            return false
        }
        pending = entry.copy(expiresAt = maxOf(entry.expiresAt, now + totalMillisFromNow))
        return true
    }

    @Synchronized
    fun discard(token: String) {
        if (pending?.token == token) pending = null
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    companion object {
        const val TTL_MILLIS = 20_000L
        const val NOTIFICATION_FALLBACK_TTL_MILLIS = 120_000L
    }
}

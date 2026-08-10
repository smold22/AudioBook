package com.yourapp.audiobook.data

class SourceCooldown {

    private val untilMs = mutableMapOf<String, Long>()

    fun isCoolingDown(sourceId: String): Boolean =
        untilMs[sourceId]?.let { System.currentTimeMillis() < it } ?: false

    fun mark(sourceId: String, durationMs: Long = COOLDOWN_MS) {
        untilMs[sourceId] = System.currentTimeMillis() + durationMs
    }

    companion object {
        const val COOLDOWN_MS = 5 * 60_000L
    }
}

package com.dmer.neoreaderrecords

data class AutoRefreshDecision(
    val shouldGenerate: Boolean,
    val latestBookKey: String,
    val logReason: String
)

object AutoRefreshPolicy {
    private const val HARD_MIN_INTERVAL_MS = 60_000L
    private const val CONTENT_EVENT_MIN_INTERVAL_MS = 45_000L

    fun decide(
        reason: String,
        wallpaperMode: String,
        nowMs: Long,
        minIntervalMinutes: Int,
        lastTriggerMs: Long,
        lastContentTriggerMs: Long,
        lastBookKey: String,
        latestBookKeyProvider: () -> String
    ): AutoRefreshDecision {
        val minIntervalMs = maxOf(minIntervalMinutes * 60_000L, HARD_MIN_INTERVAL_MS)
        val delta = nowMs - lastTriggerMs
        val contentDelta = nowMs - lastContentTriggerMs

        if (reason == "book_content_changed") {
            val latestBookKey = latestBookKeyProvider()
            if (contentDelta < CONTENT_EVENT_MIN_INTERVAL_MS) {
                return AutoRefreshDecision(false, latestBookKey, "skip: content_changed interval delta=${contentDelta}ms < $CONTENT_EVENT_MIN_INTERVAL_MS ms")
            }
            if (latestBookKey.isNotBlank() && latestBookKey == lastBookKey) {
                return AutoRefreshDecision(false, latestBookKey, "skip: content_changed book unchanged ($latestBookKey)")
            }
            return AutoRefreshDecision(true, latestBookKey, "run: content_changed book from [$lastBookKey] to [$latestBookKey]")
        }

        if (wallpaperMode == "COVER") {
            val latestBookKey = latestBookKeyProvider()
            if (latestBookKey.isNotBlank() && latestBookKey == lastBookKey) {
                return AutoRefreshDecision(false, latestBookKey, "skip: COVER mode book unchanged ($latestBookKey)")
            }
            if (delta < HARD_MIN_INTERVAL_MS) {
                return AutoRefreshDecision(false, latestBookKey, "skip: COVER mode hard interval delta=${delta}ms < $HARD_MIN_INTERVAL_MS ms")
            }
            return AutoRefreshDecision(true, latestBookKey, "run: COVER mode book changed from [$lastBookKey] to [$latestBookKey]")
        }

        if (delta < minIntervalMs) {
            return AutoRefreshDecision(false, "", "skip: debounce delta=${delta}ms < $minIntervalMs ms")
        }
        return AutoRefreshDecision(true, "", "run: debounce passed delta=${delta}ms >= $minIntervalMs ms")
    }
}


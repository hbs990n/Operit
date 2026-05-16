package com.ai.assistance.operit.data.model

import java.time.LocalDateTime

/** Comprehensive session token statistics for display in the token stats dialog */
data class SessionTokenStats(
    val chatId: String,
    val title: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val reasoningTokens: Int,
    val apiCallCount: Int,
    val provider: String,
    val modelName: String,
    val contextLimit: Int,
    val currentWindowSize: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    /** Context usage rate: current window size / context limit */
    val usageRate: Float get() = if (contextLimit > 0) currentWindowSize.toFloat() / contextLimit else 0f

    /**
     * Estimate context breakdown by category using 4-chars-per-token heuristic.
     * Returns map of category name to percentage.
     */
    fun estimateContextBreakdown(messages: List<ChatMessage>): ContextBreakdown {
        var systemChars = 0
        var userChars = 0
        var assistantChars = 0
        var toolChars = 0

        for (msg in messages) {
            val len = msg.content.length
            when (msg.sender) {
                "system" -> systemChars += len
                "user" -> userChars += len
                "ai" -> assistantChars += len
                "tool" -> toolChars += len
                else -> systemChars += len
            }
        }

        val totalChars = (systemChars + userChars + assistantChars + toolChars).coerceAtLeast(1)
        return ContextBreakdown(
            systemPct = systemChars * 100f / totalChars,
            userPct = userChars * 100f / totalChars,
            assistantPct = assistantChars * 100f / totalChars,
            toolPct = toolChars * 100f / totalChars
        )
    }
}

data class ContextBreakdown(
    val systemPct: Float,
    val userPct: Float,
    val assistantPct: Float,
    val toolPct: Float
)

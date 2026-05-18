package com.chronosworx.starcore

/**
 * STAR Context Manager
 *
 * Manages the token hard limit of quantized on-device models.
 * Implements smart trimming with priority ordering and auto-summarization.
 *
 * Priority (highest to lowest):
 * 1. System prompt core
 * 2. Current user message
 * 3. Recent conversation (last N turns)
 * 4. STAR retrieval context
 * 5. Older history (summarized)
 */
class ContextManager(
    private val maxTokens: Int = 3800,  // Buffer under absolute limits
    private val storage: StarStorage? = null
) {

    companion object {
        // Rough token estimator — 1 token ≈ 4 chars for English
        fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

        // How many recent messages to always keep verbatim
        const val RECENT_TURNS_KEPT = 6

        // Max chars for STAR retrieval context when budget is tight
        const val STAR_CONTEXT_TIGHT = 400
        const val STAR_CONTEXT_NORMAL = 800
    }

    data class ContextBudget(
        val systemTokens: Int,
        val starTokens: Int,
        val historyTokens: Int,
        val currentTokens: Int,
        val totalTokens: Int,
        val isOverBudget: Boolean
    )

    /**
     * Build a prompt that fits within the token limit.
     * Trims intelligently rather than hard-cutting.
     */
    fun buildTrimmedPrompt(
        systemPrompt: String,
        conversationHistory: List<Map<String, String>>,
        starContext: String,
        userMessage: String
    ): String {

        val currentTokens = estimateTokens(userMessage) + 20  // +20 for "User: " etc
        val systemTokens  = estimateTokens(systemPrompt)

        // Calculate remaining budget after fixed costs
        val fixedCost = systemTokens + currentTokens + 100  // 100 for structure overhead
        val remaining = maxTokens - fixedCost

        if (remaining <= 0) {
            // System prompt alone is too long — send minimal prompt
            return buildMinimalPrompt(systemPrompt, userMessage)
        }

        // Allocate remaining budget
        val starBudget    = (remaining * 0.25).toInt()  // 25% for STAR
        val historyBudget = remaining - starBudget       // 75% for history

        // Trim STAR context to budget
        val trimmedStar = trimStarContext(starContext, starBudget)

        // Trim history to budget
        val trimmedHistory = trimHistory(conversationHistory, historyBudget)

        return assemblePrompt(systemPrompt, trimmedStar, trimmedHistory, userMessage)
    }

    /**
     * Trim STAR context to fit budget.
     * Keeps index headers, truncates file contents.
     */
    private fun trimStarContext(starContext: String, budgetTokens: Int): String {
        if (starContext.isEmpty()) return ""
        val budgetChars = budgetTokens * 4
        return if (starContext.length <= budgetChars) {
            starContext
        } else {
            // Keep the index lines (short) and truncate at budget
            val lines = starContext.lines()
            val sb = StringBuilder()
            for (line in lines) {
                if (estimateTokens(sb.toString()) >= budgetTokens - 10) break
                sb.appendLine(line)
            }
            sb.toString().trimEnd() + "\n[... truncated for context budget ...]"
        }
    }

    /**
     * Trim conversation history to fit budget.
     */
    private fun trimHistory(
        history: List<Map<String, String>>,
        budgetTokens: Int
    ): List<Map<String, String>> {
        if (history.isEmpty()) return emptyList()

        val recent = history.takeLast(RECENT_TURNS_KEPT)
        val older  = history.dropLast(RECENT_TURNS_KEPT)

        // Calculate cost of recent messages
        val recentCost = recent.sumOf { msg ->
            estimateTokens(msg["content"] ?: "") + 10
        }

        if (recentCost >= budgetTokens) {
            // Even recent messages are too long — truncate content
            val perMessageBudget = budgetTokens / recent.size
            return recent.map { msg ->
                val content = msg["content"] ?: ""
                val maxChars = perMessageBudget * 4
                msg.toMutableMap().apply {
                    if (content.length > maxChars) {
                        put("content", content.take(maxChars) + "... [truncated]")
                    }
                }
            }
        }

        val olderBudget = budgetTokens - recentCost

        // Summarize older messages if they exist and we have budget
        return if (older.isEmpty() || olderBudget < 50) {
            recent
        } else {
            val summary = summarizeHistory(older, olderBudget)
            val summaryMsg = mapOf(
                "role" to "system",
                "content" to "[Earlier conversation summary: $summary]",
                "label" to "SUMMARY",
                "subject" to ""
            )
            listOf(summaryMsg) + recent
        }
    }

    /**
     * Summarize older history into a compact string.
     */
    private fun summarizeHistory(
        history: List<Map<String, String>>,
        budgetTokens: Int
    ): String {
        val budgetChars = budgetTokens * 4
        val sb = StringBuilder()

        // Take key moments — first message, every 3rd, last 2
        val keyMessages = mutableListOf<Map<String, String>>()
        if (history.isNotEmpty()) keyMessages.add(history.first())
        history.drop(1).dropLast(1).filterIndexed { i, _ -> i % 3 == 0 }
            .forEach { keyMessages.add(it) }
        if (history.size > 1) keyMessages.add(history.last())

        keyMessages.forEach { msg ->
            val role = if (msg["role"] == "user") "User" else "Assistant"
            val content = (msg["content"] ?: "").take(100)
            sb.append("$role: $content... ")
            if (sb.length >= budgetChars) return sb.toString().take(budgetChars)
        }

        return sb.toString().trim()
    }

    private fun assemblePrompt(
        system: String,
        starContext: String,
        history: List<Map<String, String>>,
        userMessage: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine(system)
        sb.appendLine()
        if (starContext.isNotEmpty()) {
            sb.appendLine(starContext)
            sb.appendLine()
        }
        if (history.isNotEmpty()) {
            sb.appendLine("=== CONVERSATION ===")
            history.forEach { msg ->
                val role = when {
                    msg["role"] == "user" -> "User"
                    msg["label"] == "SUMMARY" -> "Context"
                    else -> "Assistant"
                }
                sb.appendLine("$role: ${msg["content"]}")
            }
        }
        sb.appendLine("User: $userMessage")
        sb.append("Assistant:")
        return sb.toString()
    }

    private fun buildMinimalPrompt(system: String, userMessage: String): String {
        val trimmedSystem = system.take(2000) + "\n[System prompt truncated]"
        return "$trimmedSystem\n\nUser: $userMessage\nAssistant:"
    }

    fun getBudget(
        systemPrompt: String,
        starContext: String,
        history: List<Map<String, String>>,
        userMessage: String
    ): ContextBudget {
        val s = estimateTokens(systemPrompt)
        val st = estimateTokens(starContext)
        val h = history.sumOf { estimateTokens(it["content"] ?: "") + 10 }
        val c = estimateTokens(userMessage)
        val total = s + st + h + c + 100
        return ContextBudget(s, st, h, c, total, total >= maxTokens)
    }
}

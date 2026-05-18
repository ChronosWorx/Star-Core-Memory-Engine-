package com.chronosworx.starcore

import kotlinx.serialization.Serializable

@Serializable
data class StarTag(
    val subject: String,
    val description: String,
    val filePath: String,
    val lastModified: Long = System.currentTimeMillis(),
    val tokenEstimate: Int = 0
) {
    fun toContextString(): String =
        "◉ [$subject] ${description.take(80)}${if (description.length > 80) "…" else ""} [~${tokenEstimate}t]"

    fun isRelevantTo(query: String): Boolean {
        val lq = query.lowercase()
        val words = lq.split(" ", ",", ".", "?", "!").filter { it.length > 3 }
        return subject.lowercase().contains(lq) ||
            words.any { description.lowercase().contains(it) } ||
            words.any { subject.lowercase().contains(it) }
    }
}

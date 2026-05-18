package com.chronosworx.starcore

import kotlinx.serialization.Serializable

/**
 * STAR 2.0 — Sub-Index File (SIF) Node
 *
 * A SIF is a mid-level index file that lives between the Master Index
 * (Layer 1 / context window) and the raw data files (Layer 3).
 *
 * When a Master Node is queried, its SIF is loaded into the
 * Retrieval Zone, allowing the model to browse sub-categories
 * without loading all raw data simultaneously.
 */
@Serializable
data class SifNode(
    val tagId: String,           // e.g. "DATA_PNS_001"
    val subject: String,         // human-readable subject name
    val semanticDescription: String,  // 50-word semantic tag
    val pointer: String,         // path to raw data file
    val relevanceWeight: Float = 1.0f,
    val timestamp: String = "",
    val tokenEstimate: Int = 0
) {
    fun toContextString(): String =
        "◉ [$tagId] ${subject}: ${semanticDescription.take(80)}"
}

@Serializable
data class SubIndexFile(
    val parentNodeId: String,    // Master Node this SIF belongs to
    val granularityLevel: Int = 2,
    val subTags: MutableList<SifNode> = mutableListOf()
) {
    fun toContextString(): String {
        val sb = StringBuilder("=== SUB-INDEX: $parentNodeId ===\n")
        subTags.sortedByDescending { it.relevanceWeight }
            .forEach { sb.appendLine(it.toContextString()) }
        sb.append("=== END SUB-INDEX ===")
        return sb.toString()
    }

    fun findRelevant(query: String, maxResults: Int = 5): List<SifNode> {
        val lq = query.lowercase()
        return subTags
            .filter { node ->
                node.subject.lowercase().contains(lq) ||
                node.semanticDescription.lowercase().split(" ").any { word ->
                    word.length > 3 && lq.contains(word)
                }
            }
            .sortedByDescending { it.relevanceWeight }
            .take(maxResults)
    }
}

package com.chronosworx.starcore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * STAR Storage Engine — Universal Persistent Knowledge Layer
 *
 * ARCHITECTURE NOTE:
 * This class decouples memory storage from the LLM's active context window. 
 * Traditional models suffer from O(N^2) attention degradation as context grows. 
 * By maintaining a persistent file system outside the model's working memory, 
 * STAR achieves effectively infinite knowledge capacity. 
 *
 * Effective Capacity Formula:
 * K = (C * I / T)^L * (C * (1-I))
 * Where C = Context Window, I = Index Allocation, T = Tag Size, L = Tree Depth.
 */
class StarStorage(private val baseDirectory: File) {

    private val memoryDir = File(baseDirectory, "star_memory").also { it.mkdirs() }
    private val indexFile = File(baseDirectory, "star_index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val tags = mutableMapOf<String, StarTag>()

    // ── STAR 2.0 — Hierarchical Index ────────────────────────
    val indexV2 = StarIndexV2(baseDirectory)

    init { loadIndex() }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        try {
            val loaded: Map<String, StarTag> = json.decodeFromString(indexFile.readText())
            tags.putAll(loaded)
        } catch (e: Exception) { /* corrupt index — start fresh */ }
    }

    private fun saveIndex() {
        indexFile.writeText(json.encodeToString(tags))
    }

    // ── CONTEXT GENERATION ────────────────────────────────────

    fun getIndexContext(): String {
        val v2Context = indexV2.getMasterContextString()
        if (!v2Context.contains("empty")) return v2Context

        if (tags.isEmpty()) return "[STAR INDEX: empty — no memory files yet]"
        val sb = StringBuilder("=== STAR MEMORY INDEX ===\n")
        tags.values.sortedByDescending { it.lastModified }
            .forEach { sb.appendLine(it.toContextString()) }
        sb.append("=== END INDEX ===")
        return sb.toString()
    }

    // ── FILE OPERATIONS ───────────────────────────────────────

    fun writeFile(subject: String, description: String, content: String): StarTag {
        val fileName = subject.lowercase()
            .replace(" ", "_")
            .replace(Regex("[^a-z0-9_]"), "") + ".txt"
        val file = File(memoryDir, fileName)
        file.writeText(content)

        val tag = StarTag(
            subject = subject,
            description = description,
            filePath = file.absolutePath,
            lastModified = System.currentTimeMillis(),
            tokenEstimate = content.length / 4
        )
        tags[subject] = tag
        saveIndex()

        indexV2.addTag(
            subject = subject,
            description = description,
            dataFilePath = file.absolutePath,
            tokenEstimate = content.length / 4
        )

        return tag
    }

    fun appendToFile(subject: String, content: String, newDescription: String? = null): StarTag? {
        val tag = tags[subject] ?: return null
        val file = File(tag.filePath)
        if (!file.exists()) return null
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        file.appendText("\n[$ts] $content")
        val updated = tag.copy(
            description = newDescription ?: tag.description,
            lastModified = System.currentTimeMillis(),
            tokenEstimate = file.length().toInt() / 4
        )
        tags[subject] = updated
        saveIndex()
        return updated
    }

    fun readFile(subject: String): String? {
        val tag = tags[subject] ?: return null
        val file = File(tag.filePath)
        return if (file.exists()) file.readText() else null
    }

    // ── RETRIEVAL ─────────────────────────────────────────────

    fun findRelevantTags(query: String, maxResults: Int = 3): List<StarTag> {
        val v2Results = indexV2.findRelevantNodes(query, maxResults)
        if (v2Results.isNotEmpty()) {
            return v2Results.mapNotNull { sifNode ->
                tags.values.find { it.filePath == sifNode.pointer }
                    ?: StarTag(
                        subject = sifNode.subject,
                        description = sifNode.semanticDescription,
                        filePath = sifNode.pointer,
                        lastModified = System.currentTimeMillis(),
                        tokenEstimate = sifNode.tokenEstimate
                    )
            }
        }
        return tags.values
            .filter { it.isRelevantTo(query) }
            .sortedByDescending { it.lastModified }
            .take(maxResults)
    }

    fun getAllTags(): List<StarTag> =
        tags.values.sortedByDescending { it.lastModified }

    fun deleteFile(subject: String): Boolean {
        val tag = tags[subject] ?: return false
        File(tag.filePath).delete()
        tags.remove(subject)
        saveIndex()
        return true
    }

    fun getTag(subject: String): StarTag? = tags[subject]
    fun fileCount(): Int = tags.size
    fun totalTokenEstimate(): Long = tags.values.sumOf { it.tokenEstimate.toLong() }

    fun getV2Status(): String = indexV2.getStatus()
}

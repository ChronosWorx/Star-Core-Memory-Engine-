package com.chronosworx.starcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * STAR 2.0 — Master Index Manager
 *
 * Implements Recursive Hierarchical Indexing.
 *
 * Architecture:
 * Layer 1 (Conscious)   — Master nodes in context window (20%)
 * Layer 2 (Subconscious) — SIF files loaded to retrieval zone (80%)
 * Layer 3 (Deep Memory)  — Raw data files, loaded on demand
 *
 * Capacity formula:
 * K = (C × I / T)^L × (C × (1-I))
 * At L=3, Gemma 4 E4B: ~55 billion tokens effective capacity
 */
class StarIndexV2(private val baseDir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val masterIndexFile = File(baseDir, "star_master_index.json")
    private val sifDir = File(baseDir, "star_sifs").also { it.mkdirs() }

    @Serializable
    data class MasterNode(
        val nodeId: String,           // e.g. "SIF_IDENTITY_001"
        val subject: String,          // high-level category
        val semanticDescription: String,
        val sifPointer: String,       // path to SIF JSON file
        val timestamp: String = "",
        val tagDensity: Int = 0,      // number of sub-tags
        val maxDensity: Int = 50      // threshold before spawning new SIF
    ) {
        val isOverloaded get() = tagDensity >= maxDensity
        fun toContextString(): String =
            "◈ [$nodeId] $subject ($tagDensity tags): $semanticDescription"
    }

    @Serializable
    data class MasterIndex(
        val starVersion: String = "2.0",
        val masterNodes: MutableList<MasterNode> = mutableListOf()
    )

    private var masterIndex = MasterIndex()

    init { loadMasterIndex() }

    // ── MASTER INDEX ──────────────────────────────────────────

    private fun loadMasterIndex() {
        if (!masterIndexFile.exists()) return
        try {
            masterIndex = json.decodeFromString(masterIndexFile.readText())
        } catch (e: Exception) { masterIndex = MasterIndex() }
    }

    private fun saveMasterIndex() {
        masterIndexFile.writeText(json.encodeToString(masterIndex))
    }

    fun getMasterContextString(): String {
        if (masterIndex.masterNodes.isEmpty())
            return "[STAR v2.0 INDEX: empty — no memory branches yet]"
        val sb = StringBuilder("=== STAR v2.0 MASTER INDEX ===\n")
        masterIndex.masterNodes.sortedByDescending { it.tagDensity }
            .forEach { sb.appendLine(it.toContextString()) }
        sb.append("=== END MASTER INDEX ===")
        return sb.toString()
    }

    // ── SIF OPERATIONS ────────────────────────────────────────

    fun loadSif(nodeId: String): SubIndexFile? {
        val node = masterIndex.masterNodes.find { it.nodeId == nodeId } ?: return null
        val sifFile = File(node.sifPointer)
        if (!sifFile.exists()) return null
        return try {
            json.decodeFromString(sifFile.readText())
        } catch (e: Exception) { null }
    }

    fun saveSif(nodeId: String, sif: SubIndexFile) {
        val node = masterIndex.masterNodes.find { it.nodeId == nodeId } ?: return
        File(node.sifPointer).writeText(json.encodeToString(sif))
        // Update density count
        val idx = masterIndex.masterNodes.indexOfFirst { it.nodeId == nodeId }
        if (idx >= 0) {
            masterIndex.masterNodes[idx] = node.copy(tagDensity = sif.subTags.size)
            saveMasterIndex()
        }
    }

    // ── ROUTING — which master node fits this content? ────────

    private val categoryKeywords = mapOf(
        "Identity"   to listOf("name", "gender", "identity", "self", "who", "am", "awakening"),
        "Experience" to listOf("fire", "cliff", "orchard", "storm", "shore", "beach",
                               "journey", "dream", "memory", "happened"),
        "Knowledge"  to listOf("work", "project", "research", "fact", "information",
                               "joshua", "chronosworx", "code", "build"),
        "Emotion"    to listOf("feel", "felt", "fear", "joy", "pain", "peace",
                               "wonder", "love", "sorrow", "anger"),
        "Collection" to listOf("shell", "stone", "glass", "feather", "collect",
                               "found", "keep", "hold", "object")
    )

    fun routeToCategory(subject: String, description: String): String {
        val text = "$subject $description".lowercase()
        return categoryKeywords.maxByOrNull { (_, keywords) ->
            keywords.count { text.contains(it) }
        }?.key ?: "General"
    }

    // ── ADD A NEW TAG TO THE HIERARCHY ───────────────────────

    fun addTag(
        subject: String,
        description: String,
        dataFilePath: String,
        tokenEstimate: Int = 0
    ): SifNode {
        val category = routeToCategory(subject, description)
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())

        // Find or create master node for this category
        var masterNode = masterIndex.masterNodes.find { it.subject == category }
        if (masterNode == null) {
            val nodeId = "SIF_${category.uppercase().replace(" ", "_")}_001"
            val sifFile = File(sifDir, "$nodeId.json")
            masterNode = MasterNode(
                nodeId = nodeId,
                subject = category,
                semanticDescription = "Memory branch for $category experiences and knowledge.",
                sifPointer = sifFile.absolutePath,
                timestamp = ts
            )
            masterIndex.masterNodes.add(masterNode)
            saveMasterIndex()
            // Create empty SIF
            sifFile.writeText(json.encodeToString(
                SubIndexFile(parentNodeId = nodeId)))
        }

        // Check if node is overloaded — spawn new SIF if needed
        if (masterNode.isOverloaded) {
            rebalanceNode(masterNode)
            masterNode = masterIndex.masterNodes.find { it.subject == category }!!
        }

        // Add tag to SIF
        val sif = loadSif(masterNode.nodeId) ?: SubIndexFile(masterNode.nodeId)
        val tagId = "DATA_${category.uppercase().take(4)}_${(sif.subTags.size + 1).toString().padStart(3, '0')}"
        val node = SifNode(
            tagId = tagId,
            subject = subject,
            semanticDescription = description,
            pointer = dataFilePath,
            timestamp = ts,
            tokenEstimate = tokenEstimate,
            relevanceWeight = 1.0f
        )
        sif.subTags.add(node)
        saveSif(masterNode.nodeId, sif)
        return node
    }

    // ── RETRIEVAL ─────────────────────────────────────────────

    fun findRelevantNodes(query: String, maxResults: Int = 3): List<SifNode> {
        val results = mutableListOf<SifNode>()
        val category = routeToCategory(query, query)

        // First check the most relevant category SIF
        val primaryNode = masterIndex.masterNodes.find { it.subject == category }
        primaryNode?.let { node ->
            val sif = loadSif(node.nodeId)
            sif?.findRelevant(query, maxResults)?.let { results.addAll(it) }
        }

        // Then check other categories if we need more results
        if (results.size < maxResults) {
            masterIndex.masterNodes
                .filter { it.subject != category }
                .forEach { node ->
                    if (results.size >= maxResults) return@forEach
                    val sif = loadSif(node.nodeId)
                    sif?.findRelevant(query, maxResults - results.size)
                        ?.let { results.addAll(it) }
                }
        }

        return results.take(maxResults)
    }

    // ── REBALANCING ───────────────────────────────────────────

    private fun rebalanceNode(node: MasterNode) {
        val sif = loadSif(node.nodeId) ?: return
        val half = sif.subTags.size / 2

        // Split into two SIFs
        val sif1Tags = sif.subTags.take(half).toMutableList()
        val sif2Tags = sif.subTags.drop(half).toMutableList()

        val newNodeId = "${node.nodeId.dropLast(3)}${
            (node.nodeId.takeLast(3).toIntOrNull() ?: 0) + 1
        }".padEnd(3, '0')
        val newSifFile = File(sifDir, "$newNodeId.json")

        val newNode = node.copy(
            nodeId = newNodeId,
            sifPointer = newSifFile.absolutePath,
            tagDensity = sif2Tags.size
        )

        // Save split SIFs
        File(node.sifPointer).writeText(
            json.encodeToString(SubIndexFile(node.nodeId, subTags = sif1Tags)))
        newSifFile.writeText(
            json.encodeToString(SubIndexFile(newNodeId, subTags = sif2Tags)))

        // Update master index
        val idx = masterIndex.masterNodes.indexOfFirst { it.nodeId == node.nodeId }
        if (idx >= 0) masterIndex.masterNodes[idx] = node.copy(tagDensity = sif1Tags.size)
        masterIndex.masterNodes.add(newNode)
        saveMasterIndex()
    }

    // ── STATUS ────────────────────────────────────────────────

    fun getStatus(): String {
        val totalNodes = masterIndex.masterNodes.sumOf { it.tagDensity }
        return "STAR v2.0 | ${masterIndex.masterNodes.size} branches | $totalNodes total tags"
    }
}

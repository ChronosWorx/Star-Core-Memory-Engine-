package com.chronosworx.starcore

import java.io.File

/**
 * STAR Memory Engine — Main API Facade
 *
 * DESIGN PHILOSOPHY:
 * This class acts as the universal orchestration layer between a raw LLM inference 
 * engine and the Iterative Compositional Retrieval (ICR) memory tree. 
 *
 * Most developers attempt to solve memory by feeding raw documents directly into 
 * the active context window. This creates "context bloat," causing models to degrade, 
 * hallucinate, and spike in compute costs. 
 *
 * The STAR Memory Engine treats the context window as a transient "CPU Scratch Pad". 
 * By maintaining a permanent semantic index (Layer 1) and intelligently pulling 
 * discrete leaf nodes (Layer 3) only when mathematically necessary, this engine 
 * grants standard local edge models the effective knowledge synthesis capacity of 
 * multi-trillion token enterprise cloud setups.
 *
 * @param baseDirectory The root path where the deterministic file system will live.
 * @param maxTokens The hard ceiling for your model's context (e.g., 4096 for local Gemma).
 */
class StarMemoryEngine(
    baseDirectory: File,
    maxTokens: Int = 3800 // Buffer space included
) {
    /** * The underlying file I/O layer. Exposed publicly so advanced developers 
     * can manually parse the `StarIndexV2` tree if they are building custom UI maps.
     */
    val storage = StarStorage(baseDirectory)
    
    /** * The cryptographic token budgeter. Dynamically scales prompt elements to 
     * ensure OOM (Out Of Memory) failures literally cannot happen.
     */
    private val contextManager = ContextManager(maxTokens, storage)

    /**
     * Commits a new memory to the persistent external substrate.
     * This data is instantly indexed, mathematically balanced into the SIF 
     * (Sub-Index File) tree, and cleared from active working memory.
     *
     * @param subject The core concept/title.
     * @param description A dense, semantic tag used for deterministic routing.
     * @param content The raw, unbounded data payload to be stored.
     */
    fun saveMemory(subject: String, description: String, content: String) {
        storage.writeFile(subject, description, content)
    }

    /**
     * Appends new data to an existing memory node. Updates the timestamp 
     * to ensure recency-biased retrieval loops pull the freshest data.
     */
    fun appendMemory(subject: String, content: String, newDescription: String? = null) {
        storage.appendToFile(subject, content, newDescription)
    }

    /**
     * The Engine's Core Orchestrator. 
     * Call this exactly ONE step before sending the prompt to your LLM.
     *
     * @param systemPrompt The core identity instructions.
     * @param history The raw JSON/Map of the recent chat loop.
     * @param userQuery The incoming user message.
     * @return A fully compiled, inference-ready string optimized for local GPUs/CPUs.
     */
    fun buildOptimizedPrompt(
        systemPrompt: String, 
        history: List<Map<String, String>>, 
        userQuery: String
    ): String {
        // Fetches the index map, prioritizing the Layer 1 Master Node 
        // to keep context highly compressed, avoiding flat-sequence degradation.
        val dynamicIndexContext = storage.getIndexContext()
        
        // Assembles the string with strict priority: System > Query > Recent Chat > Index
        return contextManager.buildTrimmedPrompt(
            systemPrompt = systemPrompt,
            conversationHistory = history,
            starContext = dynamicIndexContext,
            userMessage = userQuery
        )
    }

    /**
     * Performs a direct search of the underlying knowledge base without 
     * consuming LLM compute cycles. 
     */
    fun retrieveDirectData(query: String, maxResults: Int = 3): List<StarTag> {
        return storage.findRelevantTags(query, maxResults)
    }
}

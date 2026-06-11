package com.example.rokidphone.service.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

data class KnowledgeBaseProfile(
    val id: String,
    val name: String,
    val description: String,
    val asset: String,
    val chunkCount: Int = 0,
    val includedChars: Int = 0
)

data class KnowledgeBasePrompt(
    val prompt: String,
    val profile: KnowledgeBaseProfile?,
    val contextChars: Int,
    val sourceCount: Int
)

object KnowledgeBaseRepository {
    private const val TAG = "KnowledgeBaseRepository"
    private const val MANIFEST_ASSET = "knowledge_bases/manifest.json"
    private const val MAX_CONTEXT_CHARS = 12_000
    private const val MAX_CONTEXT_CHUNKS = 8
    private const val MAX_SOURCE_LABEL_CHARS = 80

    @Volatile
    private var cachedManifest: List<KnowledgeBaseProfile>? = null

    @Volatile
    private var cachedSnapshots: Map<String, KnowledgeBaseSnapshot> = emptyMap()

    suspend fun loadProfiles(context: Context): List<KnowledgeBaseProfile> = withContext(Dispatchers.IO) {
        cachedManifest ?: readProfiles(context).also { cachedManifest = it }
    }

    suspend fun buildPrompt(
        context: Context,
        basePrompt: String,
        knowledgeBaseId: String = KnowledgeBaseStore.getSelectedKnowledgeBaseId(context)
    ): KnowledgeBasePrompt = withContext(Dispatchers.IO) {
        if (knowledgeBaseId == KnowledgeBaseStore.NONE_ID) {
            return@withContext KnowledgeBasePrompt(basePrompt, null, 0, 0)
        }

        val profile = loadProfiles(context).firstOrNull { it.id == knowledgeBaseId }
        if (profile == null) {
            Log.w(TAG, "Unknown knowledge base id: $knowledgeBaseId")
            return@withContext KnowledgeBasePrompt(basePrompt, null, 0, 0)
        }

        val snapshot = readSnapshot(context, profile)
        if (snapshot == null || snapshot.chunks.isEmpty()) {
            return@withContext KnowledgeBasePrompt(basePrompt, profile, 0, 0)
        }

        val selectedChunks = selectChunks(basePrompt, snapshot.chunks)
        val contextText = selectedChunks.joinToString("\n\n") { chunk ->
            "[${chunk.title} | ${chunk.source.shortSourceLabel()}]\n${chunk.text}"
        }.take(MAX_CONTEXT_CHARS)

        val augmentedPrompt = buildString {
            append(basePrompt.ifBlank { CodexRelayConfig.defaultPrompt })
            append("\n\n")
            append("已选择知识库：")
            append(profile.name)
            append("。请优先结合下方知识库片段回答；如果图片题目与知识库无关，请直接说明并按图片内容回答。")
            append("\n\n")
            append("知识库片段：\n")
            append(contextText)
        }

        KnowledgeBasePrompt(
            prompt = augmentedPrompt,
            profile = profile,
            contextChars = contextText.length,
            sourceCount = selectedChunks.map { it.source }.distinct().size
        )
    }

    private fun readProfiles(context: Context): List<KnowledgeBaseProfile> {
        val fromManifest = runCatching {
            val manifest = JSONObject(context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() })
            manifest.optJSONArray("profiles").orEmpty().mapObjects { profile ->
                KnowledgeBaseProfile(
                    id = profile.getString("id"),
                    name = profile.getString("name"),
                    description = profile.optString("description"),
                    asset = profile.getString("asset"),
                    chunkCount = profile.optInt("chunkCount"),
                    includedChars = profile.optInt("includedChars")
                )
            }
        }.onFailure {
            Log.w(TAG, "Knowledge base manifest unavailable; using fallback profiles", it)
        }.getOrDefault(emptyList())

        return (fromManifest.ifEmpty { fallbackProfiles() })
    }

    private fun readSnapshot(
        context: Context,
        profile: KnowledgeBaseProfile
    ): KnowledgeBaseSnapshot? {
        cachedSnapshots[profile.id]?.let { return it }

        return runCatching {
            val json = JSONObject(context.assets.open(profile.asset).bufferedReader().use { it.readText() })
            KnowledgeBaseSnapshot(
                id = json.getString("id"),
                chunks = json.optJSONArray("chunks").orEmpty().mapObjects { chunk ->
                    KnowledgeBaseChunk(
                        id = chunk.getString("id"),
                        title = chunk.optString("title"),
                        source = chunk.optString("source"),
                        rank = chunk.optInt("rank"),
                        text = chunk.optString("text")
                    )
                }.filter { it.text.isNotBlank() }
            )
        }.onSuccess { snapshot ->
            cachedSnapshots = cachedSnapshots + (profile.id to snapshot)
        }.onFailure {
            Log.w(TAG, "Unable to read knowledge base asset ${profile.asset}", it)
        }.getOrNull()
    }

    private fun selectChunks(
        query: String,
        chunks: List<KnowledgeBaseChunk>
    ): List<KnowledgeBaseChunk> {
        val queryTerms = tokenize(query)
        val scored = chunks.map { chunk ->
            val searchable = "${chunk.title} ${chunk.source} ${chunk.text}".lowercase(Locale.ROOT)
            val score = queryTerms.sumOf { term ->
                if (term.length <= 1) {
                    if (searchable.contains(term)) 1 else 0
                } else {
                    occurrences(searchable, term) * max(2, term.length)
                }
            }
            ScoredChunk(chunk, score)
        }

        val sorted = if (queryTerms.isEmpty() || scored.maxOfOrNull { it.score } == 0) {
            scored.sortedBy { it.chunk.rank }
        } else {
            scored.sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.rank })
        }

        val selected = mutableListOf<KnowledgeBaseChunk>()
        var totalChars = 0
        for (entry in sorted) {
            if (selected.size >= MAX_CONTEXT_CHUNKS) break
            if (totalChars >= MAX_CONTEXT_CHARS) break
            selected += entry.chunk
            totalChars += entry.chunk.text.length
        }
        return selected.sortedBy { it.rank }
    }

    private fun tokenize(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val words = Regex("[\\p{L}\\p{N}_]{2,}")
            .findAll(lower)
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toMutableSet()

        val cjkChars = lower.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        for (index in 0 until cjkChars.length - 1) {
            words += cjkChars.substring(index, index + 2)
        }
        return words.take(64).toSet()
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun fallbackProfiles(): List<KnowledgeBaseProfile> {
        return listOf(
            KnowledgeBaseProfile(
                id = KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID,
                name = "Software Engineering",
                description = "Course notes, review material, and assignment references.",
                asset = "knowledge_bases/software_engineering.json"
            ),
            KnowledgeBaseProfile(
                id = KnowledgeBaseStore.FRENCH_ID,
                name = "French TCF/TEF",
                description = "French reading, vocabulary, TCF/TEF practice, and OCR notes.",
                asset = "knowledge_bases/french.json"
            )
        )
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
        val items = mutableListOf<T>()
        for (index in 0 until length()) {
            optJSONObject(index)?.let { items += transform(it) }
        }
        return items
    }

    private fun String.shortSourceLabel(): String {
        return if (length <= MAX_SOURCE_LABEL_CHARS) this else takeLast(MAX_SOURCE_LABEL_CHARS)
    }

    private data class KnowledgeBaseSnapshot(
        val id: String,
        val chunks: List<KnowledgeBaseChunk>
    )

    private data class KnowledgeBaseChunk(
        val id: String,
        val title: String,
        val source: String,
        val rank: Int,
        val text: String
    )

    private data class ScoredChunk(
        val chunk: KnowledgeBaseChunk,
        val score: Int
    )

    private val STOP_WORDS = setOf(
        "the",
        "and",
        "for",
        "with",
        "this",
        "that",
        "answer",
        "image",
        "prompt",
        "example",
        "如果",
        "回答",
        "题目",
        "图片",
        "图中",
        "正确",
        "选项",
        "解释"
    )
}

package com.example.rokidphone.service.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln

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

data class KnowledgeBaseChoice(
    val profile: KnowledgeBaseProfile,
    val matchScore: Double,
    val contextChars: Int = 0,
    val sourceCount: Int = 0
)

object KnowledgeBaseRepository {
    private const val TAG = "KnowledgeBaseRepository"
    private const val MANIFEST_ASSET = "knowledge_bases/manifest.json"
    private const val MAX_CONTEXT_CHARS = 12_000
    private const val MAX_CONTEXT_CHUNKS = 8
    private const val AUTO_MIX_CHUNKS_PER_PROFILE = 3
    private const val MAX_SOURCE_LABEL_CHARS = 80
    private const val MAX_QUERY_TERMS = 24
    private const val MIN_SELECTED_CHUNK_CHARS = 80
    private const val AUTO_CONFIDENCE_THRESHOLD = 1.0
    private const val RRF_K = 48.0
    private const val MMR_RELEVANCE_WEIGHT = 0.78

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
        when (knowledgeBaseId) {
            KnowledgeBaseStore.NONE_ID -> KnowledgeBasePrompt(basePrompt, null, 0, 0)
            KnowledgeBaseStore.AUTO_ID -> buildAutoPrompt(context, basePrompt)
            else -> buildManualPrompt(context, basePrompt, knowledgeBaseId)
        }
    }

    suspend fun buildAutoPrompt(
        context: Context,
        basePrompt: String
    ): KnowledgeBasePrompt = withContext(Dispatchers.IO) {
        val choices = scoreProfiles(context, basePrompt)
        val selected = choices.maxByOrNull { it.matchScore }
        if (selected == null) {
            KnowledgeBasePrompt(basePrompt, null, 0, 0)
        } else if (selected.matchScore >= AUTO_CONFIDENCE_THRESHOLD) {
            buildPromptForProfile(context, basePrompt, selected.profile)
        } else {
            buildMixedAutoPrompt(context, basePrompt)
        }
    }

    suspend fun selectBestKnowledgeBase(
        context: Context,
        query: String
    ): KnowledgeBaseChoice? = withContext(Dispatchers.IO) {
        val profiles = loadProfiles(context)
        val scored = scoreProfiles(context, query)

        val best = scored.maxByOrNull { it.matchScore }
        if (best == null || best.matchScore <= 0.0) {
            // Fall back to the default software engineering set when the query is too vague.
            val fallback = profiles.firstOrNull { it.id == KnowledgeBaseStore.SOFTWARE_ENGINEERING_ID }
                ?: profiles.firstOrNull()
            fallback?.let { KnowledgeBaseChoice(profile = it, matchScore = 0.0) }
        } else {
            best
        }
    }

    private suspend fun buildManualPrompt(
        context: Context,
        basePrompt: String,
        knowledgeBaseId: String
    ): KnowledgeBasePrompt = withContext(Dispatchers.IO) {
        val profile = loadProfiles(context).firstOrNull { it.id == knowledgeBaseId }
        if (profile == null) {
            Log.w(TAG, "Unknown knowledge base id: $knowledgeBaseId")
            return@withContext KnowledgeBasePrompt(basePrompt, null, 0, 0)
        }
        buildPromptForProfile(context, basePrompt, profile)
    }

    private suspend fun scoreProfiles(
        context: Context,
        query: String
    ): List<KnowledgeBaseChoice> = withContext(Dispatchers.IO) {
        loadProfiles(context).mapNotNull { profile ->
            val snapshot = readSnapshot(context, profile) ?: return@mapNotNull null
            if (snapshot.chunks.isEmpty()) return@mapNotNull null

            KnowledgeBaseChoice(
                profile = profile,
                matchScore = scoreKnowledgeBase(query, snapshot)
            )
        }
    }

    private suspend fun buildMixedAutoPrompt(
        context: Context,
        basePrompt: String
    ): KnowledgeBasePrompt = withContext(Dispatchers.IO) {
        val profiles = loadProfiles(context)
        val selectedByProfile = profiles.mapNotNull { profile ->
            val snapshot = readSnapshot(context, profile) ?: return@mapNotNull null
            val selected = selectChunks(basePrompt, snapshot.chunks)
                .take(AUTO_MIX_CHUNKS_PER_PROFILE)
            if (selected.isEmpty()) null else profile to selected
        }

        val selected = mutableListOf<ProfileChunk>()
        for (round in 0 until AUTO_MIX_CHUNKS_PER_PROFILE) {
            for ((profile, chunks) in selectedByProfile) {
                chunks.getOrNull(round)?.let { chunk ->
                    selected += ProfileChunk(profile, chunk)
                    if (selected.size >= MAX_CONTEXT_CHUNKS) break
                }
            }
            if (selected.size >= MAX_CONTEXT_CHUNKS) break
        }

        if (selected.isEmpty()) {
            return@withContext KnowledgeBasePrompt(basePrompt, null, 0, 0)
        }

        val contextText = selected.joinToString("\n\n") { item ->
            "[${item.profile.name} | ${item.chunk.title} | ${item.chunk.source.shortSourceLabel()}]\n${item.chunk.text}"
        }.take(MAX_CONTEXT_CHARS)

        val mixedProfile = KnowledgeBaseProfile(
            id = KnowledgeBaseStore.AUTO_ID,
            name = "Auto Knowledge Mix",
            description = "Mixed context from available knowledge bases.",
            asset = "",
            chunkCount = profiles.sumOf { it.chunkCount },
            includedChars = profiles.sumOf { it.includedChars }
        )
        val augmentedPrompt = buildString {
            append(basePrompt.ifBlank { CodexRelayConfig.defaultPrompt })
            append("\n\n")
            append("已选择知识库：")
            append(mixedProfile.name)
            append("。当前题目文字不足以可靠判断所属领域，因此自动混合多个知识库的高优先级片段。")
            append("请优先结合与图片题目最相关的片段回答；无关片段请忽略。")
            append("\n\n")
            append("知识库片段：\n")
            append(contextText)
        }

        KnowledgeBasePrompt(
            prompt = augmentedPrompt,
            profile = mixedProfile,
            contextChars = contextText.length,
            sourceCount = selected.map { "${it.profile.id}:${it.chunk.source}" }.distinct().size
        )
    }

    private suspend fun buildPromptForProfile(
        context: Context,
        basePrompt: String,
        profile: KnowledgeBaseProfile
    ): KnowledgeBasePrompt = withContext(Dispatchers.IO) {
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

        return fromManifest.ifEmpty { fallbackProfiles() }
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
        val ranked = rankChunks(query, chunks, includeRankPrior = true)
        val selected = selectDiverseChunks(ranked)
        return selected.map { it.chunk }
    }

    private fun scoreKnowledgeBase(query: String, snapshot: KnowledgeBaseSnapshot): Double {
        val plan = buildQueryPlan(query)
        if (!plan.hasSpecificSignals) return 0.0
        return rankChunks(query, snapshot.chunks, includeRankPrior = false)
            .take(MAX_CONTEXT_CHUNKS * 2)
            .sumOf { it.score }
    }

    private fun rankChunks(
        query: String,
        chunks: List<KnowledgeBaseChunk>,
        includeRankPrior: Boolean
    ): List<ScoredChunk> {
        val plan = buildQueryPlan(query)
        val fused = linkedMapOf<String, Double>()

        fun addRanking(ranking: List<KnowledgeBaseChunk>, weight: Double) {
            ranking.forEachIndexed { index, chunk ->
                fused[chunk.id] = (fused[chunk.id] ?: 0.0) + weight / (RRF_K + index + 1.0)
            }
        }

        addRanking(
            scoreAndRank(chunks) { chunk -> lexicalScore(chunk, plan.baseTerms) },
            weight = 1.0
        )
        addRanking(
            scoreAndRank(chunks) { chunk -> lexicalScore(chunk, plan.expandedTerms) },
            weight = 0.82
        )
        addRanking(
            scoreAndRank(chunks) { chunk -> phraseScore(chunk, plan.phrases) },
            weight = 0.7
        )
        addRanking(
            scoreAndRank(chunks) { chunk -> conceptScore(chunk, plan.concepts) },
            weight = 0.62
        )
        addRanking(
            scoreAndRank(chunks) { chunk -> topicAffinityScore(chunk, plan) },
            weight = 1.15
        )
        if (includeRankPrior && !plan.hasSpecificSignals) {
            addRanking(chunks.sortedBy { it.rank }, weight = 0.4)
        }

        val byId = chunks.associateBy { it.id }
        return fused.entries
            .mapNotNull { (chunkId, score) ->
                byId[chunkId]?.let { chunk ->
                    val directScore = lexicalScore(chunk, plan.expandedTerms) + phraseScore(chunk, plan.phrases)
                    val topicAffinity = topicAffinityScore(chunk, plan)
                    val topicPenalty = topicMismatchPenalty(chunk, plan)
                    ScoredChunk(chunk, score + directScore / 1200.0 + topicAffinity / 100.0 - topicPenalty / 100.0)
                }
            }
            .filter { it.score > 0.0 || includeRankPrior }
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.rank })
    }

    private fun selectDiverseChunks(ranked: List<ScoredChunk>): List<ScoredChunk> {
        val selected = mutableListOf<KnowledgeBaseChunk>()
        val selectedScored = mutableListOf<ScoredChunk>()
        var totalChars = 0
        val candidates = ranked.toMutableList()

        while (candidates.isNotEmpty()) {
            if (selected.size >= MAX_CONTEXT_CHUNKS) break
            if (totalChars >= MAX_CONTEXT_CHARS) break

            val next = candidates.maxBy { candidate ->
                val redundancy = selected.maxOfOrNull { similarity(candidate.chunk, it) } ?: 0.0
                val sameSourcePenalty = if (selected.any { it.source == candidate.chunk.source }) 0.08 else 0.0
                (MMR_RELEVANCE_WEIGHT * candidate.score) -
                    ((1.0 - MMR_RELEVANCE_WEIGHT) * redundancy) -
                    sameSourcePenalty
            }
            candidates.remove(next)
            if (next.chunk.text.length < MIN_SELECTED_CHUNK_CHARS) continue

            selected += next.chunk
            selectedScored += next
            totalChars += next.chunk.text.length
        }

        return selectedScored
    }

    private fun scoreAndRank(
        chunks: List<KnowledgeBaseChunk>,
        score: (KnowledgeBaseChunk) -> Double
    ): List<KnowledgeBaseChunk> {
        return chunks
            .map { ScoredChunk(it, score(it)) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.rank })
            .map { it.chunk }
    }

    private fun buildQueryPlan(query: String): QueryPlan {
        val baseTerms = tokenize(query)
        val phrases = extractPhrases(query)
        val concepts = matchedConcepts(query)
        val expandedTerms = (baseTerms + concepts.flatMap { concept ->
            CONCEPT_EXPANSIONS[concept].orEmpty().flatMap(::tokenize)
        }).take(MAX_QUERY_TERMS).toSet()

        return QueryPlan(
            baseTerms = baseTerms,
            expandedTerms = expandedTerms,
            phrases = phrases,
            concepts = concepts,
            hasSpecificSignals = baseTerms.isNotEmpty() || concepts.isNotEmpty()
        )
    }

    private fun lexicalScore(chunk: KnowledgeBaseChunk, terms: Set<String>): Double {
        if (terms.isEmpty()) return 0.0
        val searchable = chunk.searchableText()
        return terms.sumOf { term ->
            val hits = termOccurrences(searchable, term)
            if (hits == 0) {
                0.0
            } else {
                val titleBoost = if (containsSearchTerm(chunk.title.lowercase(Locale.ROOT), term)) 3.0 else 1.0
                val sourceBoost = if (containsSearchTerm(chunk.source.lowercase(Locale.ROOT), term)) 1.7 else 1.0
                val lengthNorm = 1.0 / (1.0 + chunk.text.length / 3200.0)
                val idfLike = ln(2.0 + term.length.coerceAtMost(12).toDouble())
                hits * titleBoost * sourceBoost * lengthNorm * idfLike
            }
        }
    }

    private fun phraseScore(chunk: KnowledgeBaseChunk, phrases: Set<String>): Double {
        if (phrases.isEmpty()) return 0.0
        val searchable = chunk.searchableText()
        return phrases.sumOf { phrase ->
            occurrences(searchable, phrase) * (8.0 + phrase.length.coerceAtMost(30) / 3.0)
        }
    }

    private fun conceptScore(chunk: KnowledgeBaseChunk, concepts: Set<String>): Double {
        if (concepts.isEmpty()) return 0.0
        val searchable = chunk.searchableText()
        return concepts.sumOf { concept ->
            CONCEPT_EXPANSIONS[concept].orEmpty().sumOf { alias ->
                val aliasTerms = tokenize(alias)
                if (aliasTerms.isEmpty()) {
                    if (aliasMatches(searchable, alias)) 3.0 else 0.0
                } else {
                    val matched = aliasTerms.count { containsSearchTerm(searchable, it) }
                    if (matched == 0) 0.0 else matched * 2.4
                }
            }
        }
    }

    private fun topicAffinityScore(chunk: KnowledgeBaseChunk, plan: QueryPlan): Double {
        if (plan.concepts.isEmpty()) return 0.0
        val header = chunk.headerText()
        val body = chunk.text.take(1400).lowercase(Locale.ROOT)
        return plan.concepts.sumOf { concept ->
            TOPIC_MARKERS[concept].orEmpty().maxOfOrNull { marker ->
                when {
                    aliasMatches(header, marker) -> 10.0
                    aliasMatches(body, marker) -> 2.0
                    else -> 0.0
                }
            } ?: 0.0
        }
    }

    private fun topicMismatchPenalty(chunk: KnowledgeBaseChunk, plan: QueryPlan): Double {
        if (plan.concepts.isEmpty()) return 0.0
        val header = chunk.headerText()
        val headerTopics = TOPIC_MARKERS
            .filterValues { markers ->
                markers.any { marker -> aliasMatches(header, marker) }
            }
            .keys
        if (headerTopics.isEmpty()) return 0.0

        val unrelated = headerTopics.any { it !in plan.concepts }
        val related = headerTopics.any { it in plan.concepts }
        return when {
            !unrelated -> 0.0
            related -> 2.0
            else -> 28.0
        }
    }

    private fun tokenize(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val words = Regex("[A-Za-z\\u00C0-\\u00FF0-9_]{2,}")
            .findAll(lower)
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toMutableSet()

        val cjkChars = lower.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        for (index in 0 until cjkChars.length - 1) {
            val bigram = cjkChars.substring(index, index + 2)
            if (bigram !in STOP_WORDS) {
                words += bigram
            }
        }
        return words.take(MAX_QUERY_TERMS).toSet()
    }

    private fun extractPhrases(text: String): Set<String> {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
        val phrases = mutableSetOf<String>()
        Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_\\s-]{5,60}")
            .findAll(normalized)
            .map { it.value.trim() }
            .filter { phrase ->
                phrase.length >= 6 && phrase.split(Regex("\\s+")).size in 2..8
            }
            .take(6)
            .forEach { phrases += it }
        return phrases
    }

    private fun matchedConcepts(query: String): Set<String> {
        val normalized = query.lowercase(Locale.ROOT)
        return CONCEPT_EXPANSIONS.filterValues { aliases ->
            aliases.any { alias ->
                aliasMatches(normalized, alias)
            }
        }.keys
    }

    private fun aliasMatches(normalizedText: String, alias: String): Boolean {
        val normalizedAlias = alias.lowercase(Locale.ROOT)
        if (normalizedAlias.isBlank()) return false
        if (normalizedAlias.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
            return normalizedText.contains(normalizedAlias)
        }
        return Regex(
            "(?<![A-Za-z\\u00C0-\\u00FF0-9_])${Regex.escape(normalizedAlias)}(?![A-Za-z\\u00C0-\\u00FF0-9_])"
        ).containsMatchIn(normalizedText)
    }

    private fun containsSearchTerm(normalizedText: String, term: String): Boolean {
        return termOccurrences(normalizedText, term) > 0
    }

    private fun termOccurrences(normalizedText: String, term: String): Int {
        if (term.isBlank()) return 0
        if (term.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
            return occurrences(normalizedText, term)
        }
        val pattern = Regex(
            "(?<![A-Za-z\\u00C0-\\u00FF0-9_])${Regex.escape(term)}(?![A-Za-z\\u00C0-\\u00FF0-9_])"
        )
        return pattern.findAll(normalizedText).count()
    }

    private fun similarity(left: KnowledgeBaseChunk, right: KnowledgeBaseChunk): Double {
        val leftTerms = tokenize("${left.title} ${left.text.take(900)}")
        val rightTerms = tokenize("${right.title} ${right.text.take(900)}")
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) return 0.0
        val intersection = leftTerms.count { it in rightTerms }
        val union = leftTerms.size + rightTerms.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union.toDouble()
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
        val score: Double
    )

    private data class ProfileChunk(
        val profile: KnowledgeBaseProfile,
        val chunk: KnowledgeBaseChunk
    )

    private data class QueryPlan(
        val baseTerms: Set<String>,
        val expandedTerms: Set<String>,
        val phrases: Set<String>,
        val concepts: Set<String>,
        val hasSpecificSignals: Boolean
    )

    private fun KnowledgeBaseChunk.searchableText(): String {
        return "$title $source $text".lowercase(Locale.ROOT)
    }

    private fun KnowledgeBaseChunk.headerText(): String {
        return "$title $source".lowercase(Locale.ROOT)
    }

    private val CONCEPT_EXPANSIONS = mapOf(
        "process" to listOf(
            "软件过程",
            "软件生命周期",
            "生命周期",
            "瀑布",
            "螺旋",
            "rup",
            "scrum",
            "xp",
            "敏捷",
            "迭代",
            "增量",
            "过程模型",
            "software process",
            "process model",
            "agile",
            "iterative"
        ),
        "requirements" to listOf(
            "需求",
            "需求工程",
            "需求获取",
            "需求分析",
            "需求定义",
            "需求验证",
            "可验证",
            "问题陈述",
            "软件需求规约",
            "srs",
            "vision",
            "stakeholder",
            "use case",
            "用例",
            "requirement",
            "requirements"
        ),
        "design" to listOf(
            "软件设计",
            "架构",
            "模块",
            "耦合",
            "内聚",
            "高内聚",
            "低耦合",
            "uml",
            "类图",
            "顺序图",
            "architecture",
            "coupling",
            "cohesion"
        ),
        "testing" to listOf(
            "软件测试",
            "测试",
            "黑盒",
            "白盒",
            "等价类",
            "边界值",
            "覆盖率",
            "单元测试",
            "集成测试",
            "test",
            "testing"
        ),
        "project_management" to listOf(
            "项目管理",
            "软件项目管理",
            "团队管理"
        ),
        "estimation" to listOf(
            "估算",
            "成本",
            "进度",
            "人月",
            "cocomo",
            "工作量",
            "规模估算",
            "cost estimation"
        ),
        "risk_management" to listOf(
            "风险分析",
            "风险管理",
            "风险识别",
            "风险应对",
            "风险监控",
            "什么风险",
            "属于什么风险",
            "risk"
        ),
        "quality" to listOf(
            "软件质量",
            "质量属性",
            "质量管理",
            "可靠性",
            "可维护性",
            "cmmi",
            "spice",
            "iso",
            "度量",
            "quality"
        ),
        "new_progress" to listOf(
            "软件工程新进展",
            "新进展",
            "ai for se",
            "se for ai",
            "人工智能",
            "大模型"
        ),
        "french_exam" to listOf(
            "tcf",
            "tef",
            "compréhension",
            "compréhension écrite",
            "vocabulaire",
            "grammaire",
            "français"
        )
    )

    private val TOPIC_MARKERS = mapOf(
        "process" to listOf(
            "02 软件过程",
            "软件过程",
            "过程模型",
            "生命周期",
            "rup",
            "scrum",
            "xp",
            "瀑布",
            "敏捷",
            "迭代"
        ),
        "requirements" to listOf(
            "03 软件需求",
            "online-exam-usecase",
            "软件需求",
            "需求",
            "vision",
            "stakeholder",
            "use case",
            "用例",
            "问题陈述"
        ),
        "design" to listOf(
            "04 软件设计",
            "软件设计",
            "架构",
            "耦合",
            "内聚",
            "uml",
            "类图",
            "顺序图",
            "微服务"
        ),
        "testing" to listOf(
            "06 软件测试",
            "软件测试",
            "测试",
            "黑盒",
            "白盒",
            "等价类",
            "边界值",
            "覆盖"
        ),
        "project_management" to listOf(
            "07 软件项目管理",
            "软件项目管理",
            "项目管理"
        ),
        "estimation" to listOf(
            "5-14",
            "估算案例",
            "估算作业",
            "估算",
            "cocomo",
            "人月",
            "工作量"
        ),
        "risk_management" to listOf(
            "09 软件风险管理",
            "5-28",
            "风险分析",
            "风险管理",
            "风险"
        ),
        "quality" to listOf(
            "08 软件质量管理",
            "软件质量",
            "质量",
            "cmmi",
            "spice",
            "iso"
        ),
        "new_progress" to listOf(
            "11 软件工程新进展",
            "软件工程新进展",
            "ai for se",
            "se for ai",
            "人工智能",
            "大模型"
        ),
        "french_exam" to listOf(
            "tcf",
            "tef",
            "compréhension",
            "vocabulaire",
            "grammaire",
            "français"
        )
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
        "解释",
        "软件",
        "工程",
        "项目",
        "课程",
        "期末",
        "说明",
        "什么",
        "如何",
        "分别",
        "哪些",
        "问题",
        "解决",
        "应该",
        "对应",
        "影响",
        "请",
        "举例",
        "系统"
    )
}

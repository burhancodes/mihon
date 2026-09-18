package eu.kanade.tachiyomi.data.translation.grammar

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class LanguageToolService(
    private val networkHelper: NetworkHelper,
    private val preferences: ReaderPreferences,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client by lazy {
        networkHelper.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Post-processes translated texts using LanguageTool grammar correction.
     * If the server URL is blank or the request fails, the original text is returned unchanged.
     */
    suspend fun correctGrammar(
        texts: List<String>,
        targetLangKey: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val rawUrl = preferences.languageToolUrl.get().trim()
        if (rawUrl.isBlank() || texts.isEmpty()) {
            return@withContext texts
        }

        val endpoint = if (rawUrl.endsWith("/v2/check")) {
            rawUrl
        } else {
            "${rawUrl.trimEnd('/')}/v2/check"
        }

        val langCode = mapToLanguageToolLang(targetLangKey)

        coroutineScope {
            texts.map { text ->
                async {
                    correctSingleText(endpoint, text, langCode)
                }
            }.awaitAll()
        }
    }

    private fun correctSingleText(
        endpoint: String,
        text: String,
        langCode: String,
    ): String {
        if (text.isBlank()) return text

        try {
            val body = FormBody.Builder()
                .add("text", text)
                .add("language", langCode)
                .build()

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.WARN) {
                        "LanguageTool server returned HTTP ${response.code}"
                    }
                    return text
                }

                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) return text

                val ltResponse = json.decodeFromString<LanguageToolResponse>(responseBody)
                return applyCorrections(text, ltResponse.matches)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) {
                "LanguageTool grammar check failed for text: '$text', falling back to uncorrected text"
            }
            return text
        }
    }

    fun applyCorrections(
        originalText: String,
        matches: List<LanguageToolMatch>,
    ): String {
        if (matches.isEmpty() || originalText.isEmpty()) return originalText

        // 1. Filter out invalid ranges and matches without replacements
        val validMatches = matches.filter { match ->
            match.offset >= 0 &&
                match.length > 0 &&
                match.offset + match.length <= originalText.length &&
                match.replacements.any { it.value.isNotBlank() }
        }
        if (validMatches.isEmpty()) return originalText

        // 2. Resolve conflicts between mutually exclusive or overlapping matches
        val resolvedMatches = resolveConflictingMatches(originalText, validMatches)

        // 3. Apply matches in reverse order of offset (from end backwards)
        val sortedMatches = resolvedMatches.sortedByDescending { it.offset }
        var result = originalText
        var lastProcessedOffset = Int.MAX_VALUE

        for (match in sortedMatches) {
            val rawReplacement = match.replacements.firstOrNull()?.value ?: continue
            val offset = match.offset
            val length = match.length

            // Skip overlapping ranges
            if (offset + length > lastProcessedOffset) {
                continue
            }

            val originalSlice = originalText.substring(offset, offset + length)
            val replacement = matchCasing(originalSlice, rawReplacement)

            result = result.substring(0, offset) + replacement + result.substring(offset + length)
            lastProcessedOffset = offset
        }

        return result
    }

    private fun resolveConflictingMatches(
        originalText: String,
        matches: List<LanguageToolMatch>,
    ): List<LanguageToolMatch> {
        if (matches.size <= 1) return matches

        val sorted = matches.sortedWith(compareBy({ it.offset }, { it.length }))
        val clusters = mutableListOf<MutableList<LanguageToolMatch>>()
        var currentCluster = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val prev = currentCluster.last()
            val current = sorted[i]

            if (areMatchesConflicting(originalText, prev, current)) {
                currentCluster.add(current)
            } else {
                clusters.add(currentCluster)
                currentCluster = mutableListOf(current)
            }
        }
        clusters.add(currentCluster)

        return clusters.map { cluster ->
            if (cluster.size == 1) {
                cluster.first()
            } else {
                pickBestMatchFromConflictCluster(originalText, cluster)
            }
        }
    }

    private fun areMatchesConflicting(
        originalText: String,
        a: LanguageToolMatch,
        b: LanguageToolMatch,
    ): Boolean {
        // 1. Direct character range overlap
        val aEnd = a.offset + a.length
        val bStart = b.offset
        if (bStart < aEnd) return true

        // 2. Adjacent token conflict (separated only by whitespace or punctuation)
        val gap = originalText.substring(aEnd, bStart)
        val isAdjacent = gap.all { it.isWhitespace() || it in ",;:-" }

        if (isAdjacent) {
            val aIsGrammar = a.rule.category.id.equals("GRAMMAR", ignoreCase = true) ||
                a.rule.issueType.equals("grammar", ignoreCase = true)
            val bIsGrammar = b.rule.category.id.equals("GRAMMAR", ignoreCase = true) ||
                b.rule.issueType.equals("grammar", ignoreCase = true)

            // Both are grammar rules operating on adjacent tokens -> direct agreement/syntax collision
            if (aIsGrammar && bIsGrammar) return true

            // Cross-referencing: one rule's message or description mentions the other's token
            val aOriginal = originalText.substring(a.offset, aEnd)
            val bOriginal = originalText.substring(b.offset, b.offset + b.length)

            val aReferencesB = a.message.contains(bOriginal, ignoreCase = true) ||
                a.rule.description.contains(bOriginal, ignoreCase = true)
            val bReferencesA = b.message.contains(aOriginal, ignoreCase = true) ||
                b.rule.description.contains(aOriginal, ignoreCase = true)

            if (aReferencesB || bReferencesA) return true

            // Known illegal bigram creation if both were applied
            val aRepl = a.replacements.firstOrNull()?.value.orEmpty()
            val bRepl = b.replacements.firstOrNull()?.value.orEmpty()
            if (isIllegalBigram("$aRepl $bRepl")) return true
        }

        return false
    }

    private fun pickBestMatchFromConflictCluster(
        originalText: String,
        cluster: List<LanguageToolMatch>,
    ): LanguageToolMatch {
        return cluster.maxByOrNull { match ->
            scoreMatchInContext(originalText, match)
        } ?: cluster.first()
    }

    private fun scoreMatchInContext(
        originalText: String,
        match: LanguageToolMatch,
    ): Int {
        var score = 0
        val matchEnd = match.offset + match.length
        val matchOriginal = originalText.substring(match.offset, matchEnd)
        val replacement = match.replacements.firstOrNull()?.value.orEmpty()
        val afterText = originalText.substring(matchEnd).trimStart()

        // 1. Singular complement check (e.g. "a test sentence" or "an example")
        val hasSingularArticle = afterText.startsWith("a ", ignoreCase = true) ||
            afterText.startsWith("an ", ignoreCase = true) ||
            afterText.startsWith("one ", ignoreCase = true)

        if (hasSingularArticle) {
            val isSingularVerb = replacement.equals("is", ignoreCase = true) ||
                replacement.equals("was", ignoreCase = true) ||
                replacement.equals("has", ignoreCase = true)
            val isPluralToken = replacement.equals("these", ignoreCase = true) ||
                replacement.equals("those", ignoreCase = true) ||
                replacement.equals("are", ignoreCase = true) ||
                replacement.equals("were", ignoreCase = true)

            if (isSingularVerb) score += 50
            if (isPluralToken) score -= 50
        }

        // 2. Plural complement check (e.g. "test sentences", "errors", "notes")
        val endsWithPlural = afterText.split(Regex("[^a-zA-Z0-9]+"))
            .firstOrNull { it.isNotBlank() }
            ?.let { word ->
                word.endsWith("s", ignoreCase = true) && !word.endsWith("ss", ignoreCase = true)
            } ?: false

        if (endsWithPlural && !hasSingularArticle) {
            val isPluralToken = replacement.equals("these", ignoreCase = true) ||
                replacement.equals("those", ignoreCase = true) ||
                replacement.equals("are", ignoreCase = true)
            if (isPluralToken) score += 30
        }

        // 3. Subject-Verb Agreement controller vs target:
        // Verbs inflect to agree with subjects in English.
        // A rule correcting the verb is preferred over one mutating the subject pronoun/demonstrative.
        val isVerbCorrection = match.rule.id.contains("VERB", ignoreCase = true) ||
            matchOriginal.lowercase() in listOf("are", "were", "is", "was", "has", "have", "do", "does")
        if (isVerbCorrection) {
            score += 20
        }

        // 4. Illegal bigram penalty: check if this replacement alone creates an illegal bigram with immediate neighbors
        val beforeText = originalText.substring(0, match.offset).trimEnd()
        val prevWord = beforeText.split(Regex("\\s+")).lastOrNull().orEmpty()
        val nextWord = afterText.split(Regex("\\s+")).firstOrNull().orEmpty()

        if (prevWord.isNotBlank() && isIllegalBigram("$prevWord $replacement")) {
            score -= 100
        }
        if (nextWord.isNotBlank() && isIllegalBigram("$replacement $nextWord")) {
            score -= 100
        }

        // 5. Positive reward for high-confidence grammatical phrase (e.g. "This is", "These are")
        if (prevWord.isNotBlank()) {
            val phrase = "$prevWord $replacement".lowercase()
            if (phrase in
                listOf("this is", "that is", "these are", "those are", "it is", "he is", "she is", "they are")
            ) {
                score += 40
            }
        }

        return score
    }

    private fun isIllegalBigram(pair: String): Boolean {
        val normalized = pair.lowercase().trim().replace(Regex("\\s+"), " ")
        return ILLEGAL_BIGRAMS.any { normalized.startsWith(it) || normalized == it }
    }

    private fun matchCasing(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement
        return when {
            original.all { it.isUpperCase() } && original.length > 1 -> replacement.uppercase()
            original[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            else -> replacement
        }
    }

    private fun mapToLanguageToolLang(langKey: String): String {
        val key = langKey.uppercase().trim()
        return when (key) {
            "ENG", "EN" -> "en-US"
            "CHS" -> "zh-CN"
            "CHT" -> "zh-TW"
            "CSY", "CS" -> "cs-CZ"
            "NLD", "NL" -> "nl-NL"
            "FRA", "FR" -> "fr-FR"
            "DEU", "DE" -> "de-DE"
            "HUN", "HU" -> "hu-HU"
            "ITA", "IT" -> "it-IT"
            "JPN", "JA" -> "ja-JP"
            "KOR", "KO" -> "ko-KR"
            "POL", "PL" -> "pl-PL"
            "PTB", "PT" -> "pt-BR"
            "ROM", "RO" -> "ro-RO"
            "RUS", "RU" -> "ru-RU"
            "ESP", "ES" -> "es-ES"
            "TRK", "TR" -> "tr-TR"
            "VIN", "VI" -> "vi-VN"
            else -> "auto"
        }
    }

    @Serializable
    data class LanguageToolResponse(
        val matches: List<LanguageToolMatch> = emptyList(),
    )

    @Serializable
    data class LanguageToolMatch(
        val offset: Int = 0,
        val length: Int = 0,
        val replacements: List<LanguageToolReplacement> = emptyList(),
        val message: String = "",
        val shortMessage: String = "",
        val sentence: String = "",
        val rule: LanguageToolRule = LanguageToolRule(),
        val ignoreForIncompleteSentence: Boolean = false,
        val contextForSureMatch: Int = 0,
    )

    @Serializable
    data class LanguageToolRule(
        val id: String = "",
        val subId: String = "",
        val description: String = "",
        val issueType: String = "",
        val category: LanguageToolCategory = LanguageToolCategory(),
    )

    @Serializable
    data class LanguageToolCategory(
        val id: String = "",
        val name: String = "",
    )

    @Serializable
    data class LanguageToolReplacement(
        val value: String = "",
    )

    companion object {
        private val ILLEGAL_BIGRAMS = setOf(
            "these is", "those is", "this are", "that are",
            "these was", "those was", "this were", "that were",
            "he are", "she are", "it are", "they is", "they was",
            "i is", "i are", "you is", "we is", "we was",
            "are a", "were a", "are an", "were an",
        )
    }
}

package eu.kanade.tachiyomi.data.translation.rest

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
class RestTranslationService(
    private val networkHelper: NetworkHelper,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client by lazy {
        networkHelper.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val yandexUuid by lazy {
        UUID.randomUUID().toString().replace("-", "")
    }

    suspend fun translateBatch(
        texts: List<String>,
        targetLangKey: String,
        provider: String = PROVIDER_GOOGLE,
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext TranslationResult.Success(emptyList())
        }

        try {
            when (provider.lowercase()) {
                PROVIDER_YANDEX -> translateWithYandex(texts, targetLangKey)
                else -> translateWithGoogle(texts, targetLangKey)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Batch translation failed via provider $provider" }
            TranslationResult.Error(e.message ?: "Network error during translation")
        }
    }

    private fun translateWithGoogle(
        texts: List<String>,
        targetLangKey: String,
    ): TranslationResult {
        val targetLang = mapToGoogleLang(targetLangKey)

        // Google Translate expects array: [ [ [text1, text2, ...], "auto", targetLang ], "wt_lib" ]
        val payload = buildJsonArray {
            addJsonArray {
                addJsonArray {
                    texts.forEach { add(it.replace("\n", "<br>")) }
                }
                add("auto")
                add(targetLang)
            }
            add("wt_lib")
        }.toString()

        val requestBody = payload.toRequestBody("application/json+protobuf".toMediaType())
        val request = Request.Builder()
            .url(GOOGLE_TRANSLATE_URL)
            .header("X-Goog-Api-Key", GOOGLE_API_KEY)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return TranslationResult.Error("Google Translate API error: ${response.code}")
            }
            val bodyString = response.body?.string().orEmpty()
            val rootArray = json.parseToJsonElement(bodyString).jsonArray
            if (rootArray.isEmpty()) {
                return TranslationResult.Error("Empty response from Google Translate API")
            }

            val translatedArray = rootArray[0].jsonArray
            val results = translatedArray.map { element ->
                element.jsonPrimitive.content
                    .replace("<br>", "\n")
                    .replace("<br/>", "\n")
                    .replace("<br />", "\n")
            }

            return TranslationResult.Success(results)
        }
    }

    private fun translateWithYandex(
        texts: List<String>,
        targetLangKey: String,
    ): TranslationResult {
        val targetLang = mapToYandexLang(targetLangKey)
        val url = "$YANDEX_TRANSLATE_URL?srv=android&id=$yandexUuid-0-0"

        val formBuilder = FormBody.Builder().add("lang", targetLang)
        for (text in texts) {
            formBuilder.add("text", text)
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YANDEX_USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return TranslationResult.Error("Yandex Translate API error: ${response.code}")
            }
            val bodyString = response.body?.string().orEmpty()
            val rootObj = json.parseToJsonElement(bodyString)
            val textArray = rootObj.jsonArrayOrNull("text")
                ?: return TranslationResult.Error("Unexpected response format from Yandex Translate")

            val results = textArray.map { it.jsonPrimitive.content }
            return TranslationResult.Success(results)
        }
    }

    private fun JsonElement.jsonArrayOrNull(key: String) =
        try {
            jsonObject[key]?.jsonArray
        } catch (_: Exception) {
            null
        }

    sealed interface TranslationResult {
        data class Success(val translations: List<String>) : TranslationResult
        data class Error(val message: String) : TranslationResult
    }

    companion object {
        const val PROVIDER_GOOGLE = "google"
        const val PROVIDER_YANDEX = "yandex"

        private const val GOOGLE_TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml"
        private const val GOOGLE_API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"

        private const val YANDEX_TRANSLATE_URL = "https://translate.yandex.net/api/v1/tr.json/translate"
        private const val YANDEX_USER_AGENT =
            "ru.yandex.translate/21.15.4.21402814 (Xiaomi Redmi K20 Pro; Android 11)"

        val SUPPORTED_LANGUAGES = mapOf(
            "ENG" to "English",
            "CHS" to "Chinese (Simplified)",
            "CHT" to "Chinese (Traditional)",
            "CSY" to "Czech",
            "NLD" to "Dutch",
            "FRA" to "French",
            "DEU" to "German",
            "HUN" to "Hungarian",
            "ITA" to "Italian",
            "JPN" to "Japanese",
            "KOR" to "Korean",
            "POL" to "Polish",
            "PTB" to "Portuguese (Brazil)",
            "ROM" to "Romanian",
            "RUS" to "Russian",
            "ESP" to "Spanish",
            "TRK" to "Turkish",
            "VIN" to "Vietnamese",
        )

        fun mapToGoogleLang(key: String): String = when (key.uppercase()) {
            "CHS" -> "zh-CN"
            "CHT" -> "zh-TW"
            "CSY" -> "cs"
            "NLD" -> "nl"
            "FRA" -> "fr"
            "DEU" -> "de"
            "HUN" -> "hu"
            "ITA" -> "it"
            "JPN" -> "ja"
            "KOR" -> "ko"
            "POL" -> "pl"
            "PTB" -> "pt"
            "ROM" -> "ro"
            "RUS" -> "ru"
            "ESP" -> "es"
            "TRK" -> "tr"
            "VIN" -> "vi"
            else -> "en"
        }

        fun mapToYandexLang(key: String): String = when (key.uppercase()) {
            "CHS" -> "zh"
            "CHT" -> "zh"
            "CSY" -> "cs"
            "NLD" -> "nl"
            "FRA" -> "fr"
            "DEU" -> "de"
            "HUN" -> "hu"
            "ITA" -> "it"
            "JPN" -> "ja"
            "KOR" -> "ko"
            "POL" -> "pl"
            "PTB" -> "pt"
            "ROM" -> "ro"
            "RUS" -> "ru"
            "ESP" -> "es"
            "TRK" -> "tr"
            "VIN" -> "vi"
            else -> "en"
        }
    }
}

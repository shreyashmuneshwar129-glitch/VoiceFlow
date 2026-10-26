package com.voiceflow.keyboard

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

object GeminiClient {

    private const val MODEL = "gemini-2.5-flash"

    enum class LanguageMode(val label: String, val instruction: String) {
        AUTO(
            "🌐 Auto (EN / Hinglish / मराठी)",
            "Supported languages: English, Hinglish (Hindi mixed with English), and Marathi. " +
                "CRITICAL: If the speaker speaks in Hinglish or Hindi, output it in Hinglish using Roman (English) script. " +
                "If the speaker speaks in Marathi, output in Marathi (Devanagari script). " +
                "If the speaker speaks in English, output in English. " +
                "NEVER translate into another language unless the speaker explicitly says to translate."
        ),
        HINGLISH(
            "🇮🇳 Hinglish (Roman)",
            "Write the transcription in Hinglish / English using ONLY the Roman (English) alphabet. " +
                "Keep the exact Hindi/English words spoken; do NOT translate Hindi words into English."
        ),
        MARATHI(
            "🇮🇳 Marathi (मराठी)",
            "Write the transcription in Marathi (Devanagari script), keeping English technical terms natural. Do not translate unless asked."
        ),
        ENGLISH(
            "🇬🇧 English",
            "Write the transcription in clean English."
        )
    }

    enum class ToolbarAction(val title: String, val promptInstruction: String) {
        WRITE_EMAIL(
            "✉️ Write Email",
            "Turn the following text or rough voice note into a well-structured, polite, ready-to-send email with a clear subject line and body. Keep the same language unless English is more appropriate for email."
        ),
        IMPROVE(
            "✨ Improve",
            "Improve the clarity, flow, and phrasing of the following text while keeping its exact meaning and original language (if it is in Hinglish, keep it in natural Hinglish Roman script; if Marathi, keep Marathi; if English, keep English)."
        ),
        SHORTEN(
            "✂️ Shorten",
            "Make the following text concise and to-the-point while preserving the core message and original language/script."
        ),
        FIX_GRAMMAR(
            "✅ Fix Grammar",
            "Fix all spelling, grammar, and punctuation errors in the following text. Keep the original language and script (do not translate Hinglish into full English)."
        ),
        ADD_EMOJIS(
            "😊 Add Emojis",
            "Add 1 to 3 relevant, natural emojis to the following text without changing the words or language."
        ),
        MAKE_FORMAL(
            "👔 Make Formal",
            "Rewrite the following text in a professional, polite, formal tone. Keep the same language/script unless it's casual slang that needs professional phrasing."
        ),
        MAKE_CASUAL(
            "😎 Make Casual",
            "Rewrite the following text in a friendly, relaxed, casual texting tone while keeping the same language and script."
        ),
        TRANSLATE_EN(
            "🌐 Translate to English",
            "Translate the following text into natural, fluent English."
        )
    }

    suspend fun transcribeAndCleanAudio(
        apiKey: String,
        audioFile: File,
        languageMode: LanguageMode
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API key missing! Open VoiceFlow app to set it."))
        }
        if (!audioFile.exists() || audioFile.length() < 500L) {
            return@withContext Result.failure(Exception("No voice heard. Speak clearly and try again."))
        }

        try {
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            val systemPrompt = """
                You are an expert voice keyboard transcription and cleanup engine.
                Listen to the audio and output ONLY the final cleaned text to type into the user's app.

                Follow these strict rules:
                1. REMOVE FILLER WORDS: Remove "um", "uh", "er", "ah", "like", "you know", "matlab", "umm", "hmm", and accidental stutters.
                2. RESOLVE SELF-CORRECTIONS: If the speaker changes their mind or corrects themselves mid-sentence (for example: "send it at 5, no 6", "meet on Monday, sorry Wednesday", "call Rahul, nahi Rohan"), output ONLY the final intended version ("Send it at 6.", "Meet on Wednesday.", "Call Rohan.").
                3. LANGUAGE & SCRIPT: ${languageMode.instruction}
                4. DO NOT TRANSLATE unless the speaker explicitly instructs you to translate inside the audio.
                5. PUNCTUATION: Add natural punctuation and capitalization.
                6. OUTPUT ONLY THE FINAL TEXT: Do not wrap in quotes, do not explain anything, and if the audio is just silence or noise, return an empty string "".
            """.trimIndent()

            val partsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/mp4")
                        put("data", base64Audio)
                    })
                })
                put(JSONObject().apply {
                    put("text", systemPrompt)
                })
            }

            executeGeminiRequest(apiKey, partsArray)
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    suspend fun transformText(
        apiKey: String,
        inputText: String,
        action: ToolbarAction
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API key missing! Open VoiceFlow app to set it."))
        }
        if (inputText.isBlank()) {
            return@withContext Result.failure(Exception("No text found to edit. Speak or type something first!"))
        }

        try {
            val prompt = """
                ${action.promptInstruction}
                
                CRITICAL RULE: Return ONLY the final rewritten text. No explanations, no intro, no surrounding quotation marks.
                
                Text to process:
                $inputText
            """.trimIndent()

            val partsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("text", prompt)
                })
            }

            executeGeminiRequest(apiKey, partsArray)
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Please paste your API key first."))
        }
        try {
            val partsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "Reply with the exact words: VoiceFlow Ready!")
                })
            }
            executeGeminiRequest(apiKey.trim(), partsArray)
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private fun executeGeminiRequest(apiKey: String, partsArray: JSONArray): Result<String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${apiKey.trim()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 20000
            readTimeout = 35000
            doOutput = true
        }

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", partsArray)
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.15)
            })
        }

        connection.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val status = connection.responseCode
        val responseBody = if (status in 200..299) {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        }

        if (status !in 200..299) {
            return when (status) {
                429 -> Result.failure(Exception("Rate limit reached (Free Tier). Wait 30 seconds and try again."))
                400, 403 -> Result.failure(Exception("Invalid API Key. Open VoiceFlow setup to check your key."))
                500, 503 -> Result.failure(Exception("Gemini server is busy right now. Try again in a few seconds."))
                else -> Result.failure(Exception("API Error ($status). Please try again."))
            }
        }

        val json = JSONObject(responseBody)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return Result.failure(Exception("Didn't catch that. Please try speaking again."))
        }

        val content = candidates.getJSONObject(0).optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            return Result.failure(Exception("Empty response. Try speaking again."))
        }

        val output = parts.getJSONObject(0).optString("text", "").trim()
        return Result.success(output)
    }

    private fun mapException(e: Exception): Exception {
        return when (e) {
            is UnknownHostException -> Exception("No internet connection. Check your Wi-Fi or mobile data.")
            is SocketTimeoutException -> Exception("Connection timed out. Internet is slow—try again.")
            is IOException -> Exception("Network error. Please check your internet connection.")
            else -> Exception(e.message ?: "Something went wrong. Please try again.")
        }
    }
}

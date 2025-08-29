package com.merger.merger.ai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class OpenAICompatibleMergeEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val apiKeyRequired: Boolean = true
) : AiMergeEngine {

    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    override fun merge(context: ConflictContext, prefs: AiMergePrefs): AiMergeResult {
        val langLine = prefs.languageHint?.let { "Language: $it\n" } ?: ""

        val body = Request(
            model = model,
            temperature = prefs.temperature,
            maxTokens = prefs.maxTokens,
            messages = listOf(
                Message(
                    role = "system",
                    content = """
You are a merge tool. Given BASE/OURS/THEIRS, return the final merged file ONLY.

${langLine}Rules:
- Output plain file content only (NO markdown fences, NO triple backticks, NO explanations).
- The output MUST be valid ${prefs.languageHint ?: "code/text"} for direct compilation/use.
- Preserve formatting, imports/usings, encoding, and EOL style.
- Prefer OURS for local decisions unless THEIRS clearly fixes a bug.
""".trimIndent()
                ),
                Message(
                    role = "user",
                    content = """
File: ${context.filePath}

===== BASE =====
${context.base}

===== OURS =====
${context.ours}

===== THEIRS =====
${context.theirs}

Respond with the final merged file content only.
""".trimIndent()
                )
            )
        )


        val builder = HttpRequest.newBuilder()

        if (!apiKeyRequired && apiKey.isBlank()) {
            builder.uri(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        } else {
            builder.uri(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        }

        val request = builder.build();

        val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            return AiMergeResult.Failure("HTTP ${resp.statusCode()}: ${resp.body()}")
        }

        val parsed = gson.fromJson(resp.body(), Response::class.java)
        val text = parsed.choices.firstOrNull()?.message?.content?.trim()
            ?: return AiMergeResult.Failure("No content from provider")

        return AiMergeResult.Success(text)
    }

    data class Request(
        val model: String,
        @SerializedName("max_tokens") val maxTokens: Int,
        val temperature: Double,
        val messages: List<Message>
    )
    data class Message(val role: String, val content: String)
    data class Response(val choices: List<Choice>) {
        data class Choice(val message: Message)
    }
}

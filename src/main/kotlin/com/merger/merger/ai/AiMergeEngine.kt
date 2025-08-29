package com.merger.merger.ai

data class ConflictContext(
    val filePath: String,
    val base: String,
    val ours: String,
    val theirs: String
)
data class AiMergePrefs(
    val languageHint: String? = null,
    val maxTokens: Int = 4000,
    val temperature: Double = 0.1,
    val openDiffPreview: Boolean = true,
    val stageOnSuccess: Boolean = true
)
sealed class AiMergeResult {
    data class Success(val mergedContent: String) : AiMergeResult()
    data class Failure(val reason: String) : AiMergeResult()
}

interface AiMergeEngine {
    fun merge(context: ConflictContext, prefs: AiMergePrefs): AiMergeResult
}

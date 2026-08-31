package com.lishangaaa.plugins.aitranslate

sealed class TranslateResult

data class TranslateSuccess(
    val sourceLanguage: String,
    val translatedLanguage: String,
    val sourceText: String,
    val translatedText: String,
    var showingOriginal: Boolean = false
) : TranslateResult()

data class TranslateError(
    val errorCode: Int,
    val errorText: String
) : TranslateResult()

// OpenAI 协议请求与响应映射类
data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val max_tokens: Int? = null
)

data class OpenAIMessage(
    val role: String,
    val content: String
)

data class OpenAIChatResponse(
    val choices: List<OpenAIChoice>?
)

data class OpenAIChoice(
    val message: OpenAIResponseMessage?
)

data class OpenAIResponseMessage(
    val content: String?,
    val reasoning_content: String?
)

data class OpenAIModelsResponse(
    val data: List<OpenAIModelItem>?
)

data class OpenAIModelItem(
    val id: String
)
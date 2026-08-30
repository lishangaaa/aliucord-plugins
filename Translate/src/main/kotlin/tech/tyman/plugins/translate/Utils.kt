package com.lishangaaa.plugins.aitranslate

import com.aliucord.CollectionUtils
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.entries.MessageEntry

fun WidgetChatList.rerenderMessage(messageId: Long) {
    val adapter = WidgetChatList.`access$getAdapter$p`(this)
    val data = adapter.internalData
    val i = CollectionUtils.findIndex(data) { m ->
        m is MessageEntry && m.message.id == messageId
    }
    if (i != -1) adapter.notifyItemChanged(i)
}

open class TranslateData

data class TranslateSuccessData(
    val sourceLanguage: String,
    val translatedLanguage: String,
    val sourceText: String,
    val translatedText: String,
    var showingOriginal: Boolean = false
) : TranslateData()

data class TranslateErrorData(
    val errorCode: Number,
    val errorText: String
) : TranslateData()
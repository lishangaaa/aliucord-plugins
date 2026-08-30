package com.lishangaaa.plugins.aitranslate

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.aliucord.CollectionUtils
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.commands.ApplicationCommandType
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.regex.Pattern

@AliucordPlugin
class AITranslate : Plugin() {
    lateinit var pluginIcon: Drawable
    private val translatedMessages = mutableMapOf<Long, TranslateSuccessData>()
    private var chatList: WidgetChatList? = null
    private val messageLoggerEditedRegex = Pattern.compile("(?:.+ \\(.+: .+\\)\\n)+(.+)\$")

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    override fun load(ctx: Context) {
        pluginIcon = ContextCompat.getDrawable(ctx, R.e.ic_locale_24dp)!!
    }

    override fun start(context: Context) {
        patchMessageContextMenu()
        patchProcessMessageText()
        commands.registerCommand(
            "translate",
            "使用自定义 AI API 翻译文本",
            listOf(
                Utils.createCommandOption(ApplicationCommandType.STRING, "text", "需要翻译的内容"),
                Utils.createCommandOption(ApplicationCommandType.STRING, "to", "目标语言代码 (如 zh, en, ja)", choices = languageCodeChoices),
                Utils.createCommandOption(ApplicationCommandType.STRING, "from", "源语言代码 (默认 auto)", choices = languageCodeChoices),
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "send", "是否直接发送到聊天中 (默认 true)")
            )
        ) { ctx ->
            val translateData = translateMessage(
                ctx.getRequiredString("text"),
                ctx.getString("from"),
                ctx.getString("to")
            )
            if (translateData !is TranslateSuccessData) {
                with(translateData as TranslateErrorData) {
                    return@registerCommand CommandsAPI.CommandResult(
                        "$errorText ($errorCode)",
                        null,
                        false
                    )
                }
            }
            return@registerCommand CommandsAPI.CommandResult(
                translateData.translatedText,
                null,
                ctx.getBoolOrDefault("send", true)
            )
        }
    }

    private fun DraweeSpanStringBuilder.setTranslated(translateData: TranslateSuccessData, context: Context) {
        val contentStartIndex = messageLoggerEditedRegex.matcher(this.toString()).let {
            if (it.find()) {
                it.start(1)
            } else 0
        }
        this.replace(contentStartIndex, contentStartIndex + translateData.sourceText.length, translateData.translatedText)
        val textEnd = this.length
        this.append(" (AI 翻译: ${translateData.sourceLanguage} -> ${translateData.translatedLanguage})")
        this.setSpan(RelativeSizeSpan(0.75f), textEnd, this.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (textEnd != this.length) {
            this.setSpan(EditedMessageNode.Companion.`access$getForegroundColorSpan`(EditedMessageNode.Companion, context),
                textEnd, this.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun patchProcessMessageText() {
        patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook {
            chatList = it.thisObject as WidgetChatList
        })

        val mDraweeStringBuilder: Field = SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder")
        mDraweeStringBuilder.isAccessible = true
        patcher.patch(WidgetChatListAdapterItemMessage::class.java, "processMessageText", arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java), Hook {
            val messageEntry = it.args[1] as MessageEntry
            val message = messageEntry.message ?: return@Hook
            val id = message.id
            val translateData = translatedMessages[id] ?: return@Hook
            if (translateData.showingOriginal) return@Hook
            if (translateData.sourceText != message.content) {
                translatedMessages.remove(id)
                return@Hook
            }
            val textView = it.args[0] as SimpleDraweeSpanTextView
            val builder = mDraweeStringBuilder[textView] as DraweeSpanStringBuilder?
                ?: return@Hook
            val context = textView.context
            builder.setTranslated(translateData, context)
            textView.setDraweeSpanStringBuilder(builder)
        })
    }

    private fun patchMessageContextMenu() {
        val viewId = View.generateViewId()
        val messageContextMenu = WidgetChatListActions::class.java
        val getBinding = messageContextMenu.getDeclaredMethod("getBinding").apply { isAccessible = true }

        patcher.patch(messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java), Hook {
            val menu = it.thisObject as WidgetChatListActions
            val binding = getBinding.invoke(menu) as WidgetChatListActionsBinding
            val translateButton = binding.a.findViewById<TextView>(viewId)
            translateButton?.setOnClickListener { _ ->
                val message = (it.args[0] as WidgetChatListActions.Model).message
                val translationEntry = translatedMessages[message.id]

                if (translationEntry == null) {
                    Utils.threadPool.execute {
                        val response = translateMessage(message.content)
                        if (response !is TranslateSuccessData) {
                            with(response as TranslateErrorData) {
                                Utils.mainThread.post {
                                    Utils.showToast("$errorText ($errorCode)", true)
                                }
                                return@execute
                            }
                        }
                        translatedMessages[message.id] = response
                        Utils.mainThread.post {
                            chatList?.rerenderMessage(message.id)
                            Utils.showToast("已完成 AI 翻译")
                        }
                        menu.dismiss()
                    }
                } else {
                    translationEntry.showingOriginal = !translationEntry.showingOriginal
                    chatList?.rerenderMessage(message.id)
                    menu.dismiss()
                }
            }
        })

        patcher.patch(messageContextMenu, "onViewCreated", arrayOf(View::class.java, Bundle::class.java), Hook {
            val linearLayout = (it.args[0] as NestedScrollView).getChildAt(0) as LinearLayout
            val context = linearLayout.context
            val messageId = WidgetChatListActions.`access$getMessageId$p`(it.thisObject as WidgetChatListActions)
            linearLayout.addView(TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                val translationEntry = translatedMessages[messageId]

                id = viewId
                text = if (translationEntry == null || translationEntry.showingOriginal) {
                    "AI 翻译此消息"
                } else {
                    "显示原始消息"
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(pluginIcon, null, null, null)
            })
        })
    }

    override fun stop(context: Context?) = patcher.unpatchAll()

    private fun formatChatUrl(baseUrl: String): String {
        val url = baseUrl.trimEnd('/')
        return when {
            url.endsWith("/chat/completions") -> url
            url.endsWith("/v1") -> "$url/chat/completions"
            else -> "$url/v1/chat/completions"
        }
    }

    private fun translateMessage(text: String, from: String? = null, to: String? = null): TranslateData {
        val rawUrl = settings.getString("apiUrl", "https://api.openai.com/v1")
        val apiKey = settings.getString("apiKey", "")
        val model = settings.getString("model", "gpt-4o-mini")
        val toLang = to ?: settings.getString("defaultLanguage", "zh")
        val fromLang = from ?: "Auto"

        if (apiKey.isBlank()) {
            return TranslateErrorData(
                errorCode = 401,
                errorText = "请先在插件设置中填写 API Key"
            )
        }

        return try {
            val apiUrl = formatChatUrl(rawUrl)
            val systemPrompt = "You are a professional translator. Translate the given text directly to target language: $toLang. Output only the pure translated result without explanations or quotes."

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", text))
                })
            }

            val req = Http.Request(apiUrl, "POST")
                .setHeader("Authorization", "Bearer $apiKey")
                .setHeader("Content-Type", "application/json")
                .executeWithBody(jsonBody.toString())

            if (!req.ok()) {
                val errorBody = try { req.text() } catch (e: Exception) { "无法读取错误响应" }
                return TranslateErrorData(
                    errorCode = req.statusCode,
                    errorText = "API 报错: $errorBody"
                )
            }

            val resJson = JSONObject(req.text())
            val translatedContent = resJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            TranslateSuccessData(
                sourceLanguage = fromLang,
                translatedLanguage = toLang,
                sourceText = text,
                translatedText = translatedContent
            )
        } catch (e: Exception) {
            TranslateErrorData(
                errorCode = 500,
                errorText = "请求失败: ${e.localizedMessage ?: e.message}"
            )
        }
    }
}
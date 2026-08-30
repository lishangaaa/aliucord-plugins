package com.lishangaaa.plugins.aitranslate

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.commands.ApplicationCommandType
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.models.message.Message
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.regex.Pattern

@AliucordPlugin
class AITranslate : Plugin() {
    lateinit var pluginIcon: Drawable
    private val translatedMessages = mutableMapOf<Long, TranslateSuccessData>()
    private val messageViewMap = mutableMapOf<Long, WeakReference<SimpleDraweeSpanTextView>>()
    private val messageLoggerEditedRegex = Pattern.compile("(?:.+ \\(.+: .+\\)\\n)+(.+)\$")
    private val actionsMessageMap = WeakHashMap<WidgetChatListActions, Message>()
    private var draweeField: Field? = null

    companion object {
        private const val TRANSLATE_BTN_TAG = "aliucord_ai_translate_btn_tag"

        fun isBlankSafe(str: CharSequence?): Boolean {
            if (str == null) return true
            val len = str.length
            var i = 0
            while (i < len) {
                if (!Character.isWhitespace(str[i])) return false
                i++
            }
            return true
        }

        fun trimSafe(str: String?): String {
            if (str == null) return ""
            var start = 0
            val len = str.length
            var end = len - 1
            while (start <= end && Character.isWhitespace(str[start])) start++
            while (end >= start && Character.isWhitespace(str[end])) end--
            return if (start > end) "" else str.substring(start, end + 1)
        }
    }

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    override fun load(ctx: Context) {
        try {
            pluginIcon = ContextCompat.getDrawable(ctx, R.e.ic_locale_24dp)!!
        } catch (_: Throwable) {}
    }

    override fun start(context: Context) {
        patchMessageContextMenu()
        patchProcessMessageText()

        try {
            commands.registerCommand(
                "translate",
                "使用 AI API 翻译文本",
                listOf(
                    Utils.createCommandOption(ApplicationCommandType.STRING, "text", "需要翻译的内容"),
                    Utils.createCommandOption(ApplicationCommandType.STRING, "to", "目标语言或风格 (如 中文, 英语, 日文, 文言文)"),
                    Utils.createCommandOption(ApplicationCommandType.STRING, "from", "源语言 (可选，默认自动识别)"),
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
        } catch (_: Throwable) {}
    }

    private fun findDraweeField(): Field? {
        if (draweeField != null) return draweeField
        var clazz: Class<*>? = SimpleDraweeSpanTextView::class.java
        while (clazz != null && clazz != Any::class.java) {
            try {
                val f = clazz.getDeclaredField("mDraweeStringBuilder")
                f.isAccessible = true
                draweeField = f
                return f
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    private fun DraweeSpanStringBuilder.setTranslated(translateData: TranslateSuccessData, context: Context) {
        try {
            val contentStartIndex = messageLoggerEditedRegex.matcher(this.toString()).let {
                if (it.find()) it.start(1) else 0
            }
            if (contentStartIndex > 0 && contentStartIndex < this.length) {
                this.delete(contentStartIndex, this.length)
                this.append(translateData.translatedText)
            } else {
                this.clear()
                this.append(translateData.translatedText)
            }

            val textEnd = this.length
            this.append(" (AI -> ${translateData.translatedLanguage})")
            this.setSpan(RelativeSizeSpan(0.75f), textEnd, this.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            try {
                val mutedColor = ColorCompat.getThemedColor(context, R.b.colorTextMuted)
                this.setSpan(ForegroundColorSpan(mutedColor), textEnd, this.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {
            try {
                this.clear()
                this.append(translateData.translatedText)
            } catch (_: Throwable) {}
        }
    }

    private fun patchProcessMessageText() {
        try {
            findDraweeField()

            patcher.patch(WidgetChatListAdapterItemMessage::class.java, "processMessageText", arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java), Hook {
                try {
                    val messageEntry = it.args[1] as? MessageEntry ?: return@Hook
                    val message = messageEntry.message ?: return@Hook
                    val id = message.id
                    val textView = it.args[0] as? SimpleDraweeSpanTextView ?: return@Hook

                    messageViewMap[id] = WeakReference(textView)

                    val translateData = translatedMessages[id] ?: return@Hook
                    if (translateData.showingOriginal) return@Hook

                    val field = findDraweeField() ?: return@Hook
                    val builder = field.get(textView) as? DraweeSpanStringBuilder ?: return@Hook
                    val context = textView.context
                    builder.setTranslated(translateData, context)
                    textView.setDraweeSpanStringBuilder(builder)
                } catch (_: Throwable) {}
            })
        } catch (_: Throwable) {}
    }

    private fun findLinearLayout(view: View): LinearLayout? {
        if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) return view
        if (view is ViewGroup) {
            var i = 0
            val count = view.childCount
            while (i < count) {
                val found = findLinearLayout(view.getChildAt(i))
                if (found != null) return found
                i++
            }
        }
        return null
    }

    private fun updateViewDirectly(messageId: Long, successData: TranslateSuccessData?) {
        try {
            val tv = messageViewMap[messageId]?.get() ?: return
            val field = findDraweeField() ?: return
            val builder = field.get(tv) as? DraweeSpanStringBuilder ?: return
            if (successData != null && !successData.showingOriginal) {
                builder.setTranslated(successData, tv.context)
            } else {
                builder.clear()
                builder.append(successData?.sourceText ?: "")
            }
            tv.setDraweeSpanStringBuilder(builder)
            tv.invalidate()
        } catch (_: Throwable) {}
    }

    private fun patchMessageContextMenu() {
        try {
            val messageContextMenu = WidgetChatListActions::class.java
            val getBinding = messageContextMenu.getDeclaredMethod("getBinding").apply { isAccessible = true }

            fun bindButton(button: TextView, menu: WidgetChatListActions, message: Message) {
                val translationEntry = translatedMessages[message.id]
                button.text = if (translationEntry == null || translationEntry.showingOriginal) {
                    "AI 翻译此消息"
                } else {
                    "显示原始消息"
                }
                button.setOnClickListener {
                    try {
                        val currentEntry = translatedMessages[message.id]
                        if (currentEntry == null) {
                            val contentStr = message.content?.toString()
                            if (isBlankSafe(contentStr)) {
                                Utils.showToast("该消息无文本内容可翻译", true)
                                return@setOnClickListener
                            }
                            Utils.showToast("正在请求 AI 翻译...")
                            Utils.threadPool.execute {
                                val response = translateMessage(contentStr!!)
                                Utils.mainThread.post {
                                    try {
                                        if (response !is TranslateSuccessData) {
                                            val err = response as TranslateErrorData
                                            Utils.showToast("${err.errorText} (${err.errorCode})", true)
                                        } else {
                                            translatedMessages[message.id] = response
                                            updateViewDirectly(message.id, response)
                                            refreshAdapterForMessage(message.id)
                                            Utils.showToast("已完成 AI 翻译")
                                        }
                                        menu.dismiss()
                                    } catch (_: Throwable) {}
                                }
                            }
                        } else {
                            currentEntry.showingOriginal = !currentEntry.showingOriginal
                            updateViewDirectly(message.id, currentEntry)
                            refreshAdapterForMessage(message.id)
                            menu.dismiss()
                        }
                    } catch (_: Throwable) {}
                }
            }

            patcher.patch(messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java), Hook {
                try {
                    val menu = it.thisObject as? WidgetChatListActions ?: return@Hook
                    val model = it.args[0] as? WidgetChatListActions.Model ?: return@Hook
                    val message = model.message ?: return@Hook

                    actionsMessageMap[menu] = message

                    val binding = getBinding.invoke(menu) as? WidgetChatListActionsBinding ?: return@Hook
                    val translateButton = binding.root.findViewWithTag<TextView>(TRANSLATE_BTN_TAG) ?: return@Hook
                    bindButton(translateButton, menu, message)
                } catch (_: Throwable) {}
            })

            patcher.patch(messageContextMenu, "onViewCreated", arrayOf(View::class.java, Bundle::class.java), Hook {
                try {
                    val menu = it.thisObject as? WidgetChatListActions ?: return@Hook
                    val rootView = it.args[0] as? View ?: return@Hook
                    val targetLayout = findLinearLayout(rootView) ?: return@Hook
                    val context = targetLayout.context

                    // 如果已经添加过，直接取出来更新，避免重复添加
                    var button = targetLayout.findViewWithTag<TextView>(TRANSLATE_BTN_TAG)
                    if (button == null) {
                        button = TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                            tag = TRANSLATE_BTN_TAG
                            isSaveEnabled = false
                            isSaveFromParentEnabled = false
                            if (::pluginIcon.isInitialized) {
                                setCompoundDrawablesRelativeWithIntrinsicBounds(pluginIcon, null, null, null)
                            }
                        }
                        targetLayout.addView(button)
                    }

                    actionsMessageMap[menu]?.let { msg ->
                        bindButton(button, menu, msg)
                    }
                } catch (_: Throwable) {}
            })
        } catch (_: Throwable) {}
    }

    override fun stop(context: Context?) = patcher.unpatchAll()

    private fun formatChatUrl(baseUrl: String): String {
        var url = trimSafe(baseUrl)
        while (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
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
        val toLang = if (!isBlankSafe(to)) to!! else settings.getString("defaultLanguage", "中文")
        val fromLang = if (!isBlankSafe(from)) from!! else "自动识别"

        if (isBlankSafe(apiKey)) {
            return TranslateErrorData(
                errorCode = 401,
                errorText = "请先在插件设置中填写 API Key"
            )
        }

        return try {
            val apiUrl = formatChatUrl(rawUrl)
            val systemPrompt = "You are a professional translator. Directly translate the following text into '$toLang'. " +
                    "Do not output explanations, conversational filler, quotation marks, or notes. " +
                    "Only output the pure translated content."

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", text))
                })
            }

            val req = Http.Request(apiUrl, "POST")
                .setHeader("Authorization", "Bearer " + trimSafe(apiKey))
                .setHeader("Content-Type", "application/json")
                .executeWithBody(jsonBody.toString())

            if (!req.ok()) {
                val errorBody = try { req.text() } catch (e: Exception) { "无法读取响应内容" }
                return TranslateErrorData(
                    errorCode = req.statusCode,
                    errorText = "API 报错: $errorBody"
                )
            }

            val resJson = JSONObject(req.text())
            val rawTranslated = resJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            TranslateSuccessData(
                sourceLanguage = fromLang,
                translatedLanguage = toLang,
                sourceText = text,
                translatedText = trimSafe(rawTranslated)
            )
        } catch (e: Exception) {
            TranslateErrorData(
                errorCode = 500,
                errorText = "请求失败: ${e.localizedMessage ?: e.message}"
            )
        }
    }
}
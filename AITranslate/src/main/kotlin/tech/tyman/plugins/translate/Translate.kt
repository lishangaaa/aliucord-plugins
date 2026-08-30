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
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.commands.ApplicationCommandType
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
import java.util.ArrayDeque
import java.util.WeakHashMap

@AliucordPlugin
class AITranslate : Plugin() {
    private var pluginIcon: Drawable? = null
    private val translatedMessages = mutableMapOf<Long, TranslateSuccessData>()
    private val messageViewMap = mutableMapOf<Long, WeakReference<SimpleDraweeSpanTextView>>()
    private val actionsMessageMap = WeakHashMap<WidgetChatListActions, Message>()

    companion object {
        private const val TRANSLATE_BTN_TAG = "aliucord_ai_translate_btn_tag"
    }

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    override fun load(ctx: Context) {
        pluginIcon = ContextCompat.getDrawable(ctx, R.e.ic_locale_24dp)
    }

    override fun start(context: Context) {
        patchMessageContextMenu()
        patchProcessMessageText()

        commands.registerCommand(
            "translate",
            "使用 AI API 翻译文本",
            listOf(
                Utils.createCommandOption(ApplicationCommandType.STRING, "text", "需要翻译的内容", null, true),
                Utils.createCommandOption(ApplicationCommandType.STRING, "to", "目标语言 (默认配置项)"),
                Utils.createCommandOption(ApplicationCommandType.STRING, "from", "源语言 (可选)"),
                Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "send", "是否直接发送到聊天中 (默认 true)")
            )
        ) { ctx ->
            val text = ctx.getRequiredString("text")
            val to = ctx.getString("to")
            val from = ctx.getString("from")

            when (val res = translateMessage(text, from, to)) {
                is TranslateSuccessData -> CommandsAPI.CommandResult(
                    res.translatedText,
                    null,
                    ctx.getBoolOrDefault("send", true)
                )
                is TranslateErrorData -> CommandsAPI.CommandResult(
                    "${res.errorText} (${res.errorCode})",
                    null,
                    false
                )
            }
        }
    }

    private fun renderTranslatedText(textView: SimpleDraweeSpanTextView, data: TranslateSuccessData) {
        val builder = DraweeSpanStringBuilder()
        builder.append(data.translatedText)
        val start = builder.length
        builder.append(" (AI -> ${data.translatedLanguage})")
        builder.setSpan(RelativeSizeSpan(0.75f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        try {
            val mutedColor = ColorCompat.getThemedColor(textView.context, R.b.colorTextMuted)
            builder.setSpan(ForegroundColorSpan(mutedColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (_: Throwable) {}

        textView.setDraweeSpanStringBuilder(builder)
        textView.text = builder
    }

    private fun refreshChatList() {
        Utils.mainThread.post {
            val activity = Utils.appActivity ?: return@post
            val decor = activity.window?.decorView ?: return@post
            findRecyclerViews(decor).forEach { rv ->
                rv.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun findRecyclerViews(root: View): List<RecyclerView> {
        val list = ArrayList<RecyclerView>()
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is RecyclerView) {
                list.add(v)
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    queue.add(v.getChildAt(i))
                }
            }
        }
        return list
    }

    private fun patchProcessMessageText() {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook {
                val messageEntry = it.args[1] as? MessageEntry ?: return@Hook
                val message = messageEntry.message ?: return@Hook
                val textView = it.args[0] as? SimpleDraweeSpanTextView ?: return@Hook

                messageViewMap[message.id] = WeakReference(textView)

                val data = translatedMessages[message.id] ?: return@Hook
                if (!data.showingOriginal) {
                    renderTranslatedText(textView, data)
                }
            }
        )
    }

    private fun patchMessageContextMenu() {
        val messageContextMenu = WidgetChatListActions::class.java

        patcher.patch(messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java), Hook {
            val menu = it.thisObject as? WidgetChatListActions ?: return@Hook
            val model = it.args[0] as? WidgetChatListActions.Model ?: return@Hook
            val message = model.message ?: return@Hook
            actionsMessageMap[menu] = message

            val rootView = menu.view ?: return@Hook
            val button = rootView.findViewWithTag<TextView>(TRANSLATE_BTN_TAG) ?: return@Hook
            updateMenuButton(button, menu, message)
        })

        patcher.patch(messageContextMenu, "onViewCreated", arrayOf(View::class.java, Bundle::class.java), Hook {
            val menu = it.thisObject as? WidgetChatListActions ?: return@Hook
            val rootView = it.args[0] as? View ?: return@Hook
            val targetLayout = findVerticalLayout(rootView) ?: return@Hook

            var button = targetLayout.findViewWithTag<TextView>(TRANSLATE_BTN_TAG)
            if (button == null) {
                button = TextView(targetLayout.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    tag = TRANSLATE_BTN_TAG
                    pluginIcon?.let { icon -> setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null) }
                }
                targetLayout.addView(button)
            }

            actionsMessageMap[menu]?.let { msg ->
                updateMenuButton(button, menu, msg)
            }
        })
    }

    private fun updateMenuButton(button: TextView, menu: WidgetChatListActions, message: Message) {
        val entry = translatedMessages[message.id]
        button.text = if (entry == null || entry.showingOriginal) "AI 翻译此消息" else "显示原始消息"
        button.setOnClickListener {
            val current = translatedMessages[message.id]
            if (current == null) {
                val content = message.content?.toString()
                if (content.isNullOrEmpty() || content.trim().isEmpty()) {
                    Utils.showToast("该消息无文本内容", true)
                    return@setOnClickListener
                }
                Utils.showToast("正在翻译...")
                Utils.threadPool.execute {
                    val response = translateMessage(content)
                    Utils.mainThread.post {
                        if (response is TranslateSuccessData) {
                            translatedMessages[message.id] = response
                            messageViewMap[message.id]?.get()?.let { tv ->
                                renderTranslatedText(tv, response)
                            }
                            refreshChatList()
                            Utils.showToast("翻译完成")
                        } else if (response is TranslateErrorData) {
                            Utils.showToast("${response.errorText} (${response.errorCode})", true)
                        }
                        menu.dismiss()
                    }
                }
            } else {
                current.showingOriginal = !current.showingOriginal
                messageViewMap[message.id]?.get()?.let { tv ->
                    if (current.showingOriginal) {
                        val originalBuilder = DraweeSpanStringBuilder().apply { append(current.sourceText) }
                        tv.setDraweeSpanStringBuilder(originalBuilder)
                        tv.text = originalBuilder
                    } else {
                        renderTranslatedText(tv, current)
                    }
                }
                refreshChatList()
                menu.dismiss()
            }
        }
    }

    private fun findVerticalLayout(view: View): LinearLayout? {
        if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findVerticalLayout(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    override fun stop(context: Context?) = patcher.unpatchAll()

    private fun translateMessage(text: String, from: String? = null, to: String? = null): TranslateData {
        val rawUrl = settings.getString("apiUrl", "https://api.openai.com/v1")
        val apiKey = settings.getString("apiKey", "")
        val model = settings.getString("model", "gpt-4o-mini")
        val toLang = if (to != null && to.trim().isNotEmpty()) to else settings.getString("defaultLanguage", "中文")
        val fromLang = if (from != null && from.trim().isNotEmpty()) from else "自动识别"

        if (apiKey.trim().isEmpty()) {
            return TranslateErrorData(401, "请先在插件设置中填写 API Key")
        }

        return try {
            val trimmedUrl = rawUrl.trimEnd('/')
            val apiUrl = if (trimmedUrl.endsWith("/v1")) "$trimmedUrl/chat/completions" else "$trimmedUrl/v1/chat/completions"
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", "You are a professional translator. Directly translate to '$toLang'. Only output translation."))
                    put(JSONObject().put("role", "user").put("content", text))
                })
            }

            val req = Http.Request(apiUrl, "POST")
                .setHeader("Authorization", "Bearer $apiKey")
                .setHeader("Content-Type", "application/json")
                .executeWithBody(body.toString())

            if (!req.ok()) return TranslateErrorData(req.statusCode, "API 报错")

            val choices = JSONObject(req.text()).optJSONArray("choices")
                ?: return TranslateErrorData(500, "API 返回数据为空")

            val messageObj = choices.optJSONObject(0)?.optJSONObject("message")
            var content = messageObj?.optString("content", "") ?: ""
            if (content.isEmpty()) {
                content = messageObj?.optString("reasoning_content", "") ?: ""
            }

            if (content.trim().isEmpty()) return TranslateErrorData(500, "翻译内容为空")

            TranslateSuccessData(fromLang, toLang, text, content.trim())
        } catch (e: Exception) {
            TranslateErrorData(500, "请求失败: ${e.message}")
        }
    }
}
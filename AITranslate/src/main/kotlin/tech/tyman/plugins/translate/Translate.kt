package com.lishangaaa.plugins.aitranslate

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
import com.aliucord.patcher.after
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.ViewUtils
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.message.Message
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.lang.ref.WeakReference

@AliucordPlugin
class AITranslate : Plugin() {
    private var pluginIcon: Drawable? = null
    private val translatedMessages = mutableMapOf<Long, TranslateSuccess>()
    
    // 弱引用保存当前活跃的聊天列表 Adapter，用于刷新单个/全部消息视图，避免遍历 View 树
    private var currentChatAdapter: WeakReference<RecyclerView.Adapter<*>>? = null

    companion object {
        private const val TRANSLATE_BTN_TAG = "aliucord_ai_translate_btn"
    }

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    override fun load(ctx: Context) {
        pluginIcon = ContextCompat.getDrawable(ctx, R.e.ic_locale_24dp)
    }

    override fun start(context: Context) {
        patchChatListRenderer()
        patchMessageContextMenu()
        registerSlashCommands()
    }

    private fun registerSlashCommands() {
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

            when (val res = requestTranslation(text, from, to)) {
                is TranslateSuccess -> CommandsAPI.CommandResult(
                    res.translatedText,
                    null,
                    ctx.getBoolOrDefault("send", true)
                )
                is TranslateError -> CommandsAPI.CommandResult(
                    "${res.errorText} (${res.errorCode})",
                    null,
                    false
                )
            }
        }
    }

    private fun patchChatListRenderer() {
        // 使用 Aliucord 的 PatcherExtensionsKt 语法糖
        patcher.after<WidgetChatListAdapterItemMessage>(
            "processMessageText",
            SimpleDraweeSpanTextView::class.java,
            MessageEntry::class.java
        ) { param ->
            val textView = param.args[0] as? SimpleDraweeSpanTextView ?: return@after
            val messageEntry = param.args[1] as? MessageEntry ?: return@after
            val message = messageEntry.message ?: return@after

            // 拦截并渲染已翻译的内容
            val data = translatedMessages[message.id] ?: return@after
            if (!data.showingOriginal) {
                renderTranslatedText(textView, data)
            }
        }
    }

    private fun patchMessageContextMenu() {
        // 单 Hook 处理菜单，移除冗余的 onViewCreated 和 actionsMessageMap
        patcher.after<WidgetChatListActions>("configureUI", WidgetChatListActions.Model::class.java) { param ->
            val menu = this // WidgetChatListActions 实例
            val model = param.args[0] as? WidgetChatListActions.Model ?: return@after
            val message = model.message ?: return@after
            val rootView = menu.view as? ViewGroup ?: return@after

            // 查找或定位到菜单容器
            val container = ViewUtils.findViewById<LinearLayout>(rootView, "dialog_chat_actions_list")
                ?: findFirstVerticalLayout(rootView)
                ?: return@after

            var button = container.findViewWithTag<TextView>(TRANSLATE_BTN_TAG)
            if (button == null) {
                button = TextView(container.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    tag = TRANSLATE_BTN_TAG
                    pluginIcon?.let { icon -> setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null) }
                }
                container.addView(button)
            }

            bindMenuButton(button, menu, message)
        }
    }

    private fun bindMenuButton(button: TextView, menu: WidgetChatListActions, message: Message) {
        val entry = translatedMessages[message.id]
        button.text = if (entry == null || entry.showingOriginal) "AI 翻译此消息" else "显示原始消息"

        button.setOnClickListener {
            val current = translatedMessages[message.id]
            if (current == null) {
                val content = message.content?.toString()?.trim()
                if (content.isNullOrEmpty()) {
                    Utils.showToast("该消息无文本内容", true)
                    return@setOnClickListener
                }

                Utils.showToast("正在翻译...")
                Utils.threadPool.execute {
                    val res = requestTranslation(content)
                    Utils.mainThread.post {
                        if (res is TranslateSuccess) {
                            translatedMessages[message.id] = res
                            notifyAdapterChanged()
                            Utils.showToast("翻译完成")
                        } else if (res is TranslateError) {
                            Utils.showToast("${res.errorText} (${res.errorCode})", true)
                        }
                        menu.dismiss()
                    }
                }
            } else {
                current.showingOriginal = !current.showingOriginal
                notifyAdapterChanged()
                menu.dismiss()
            }
        }
    }

    private fun renderTranslatedText(textView: SimpleDraweeSpanTextView, data: TranslateSuccess) {
        val builder = DraweeSpanStringBuilder().apply {
            append(data.translatedText)
            val start = length
            append(" (AI -> ${data.translatedLanguage})")
            setSpan(RelativeSizeSpan(0.75f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            try {
                val mutedColor = ColorCompat.getThemedColor(textView.context, R.b.colorTextMuted)
                setSpan(ForegroundColorSpan(mutedColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (_: Throwable) {}
        }
        textView.setDraweeSpanStringBuilder(builder)
        textView.text = builder
    }

    private fun notifyAdapterChanged() {
        // 安全刷新当前界面的列表绑定，无卡顿且不泄露 View
        currentChatAdapter?.get()?.notifyDataSetChanged()
            ?: Utils.appActivity?.let { act ->
                act.window?.decorView?.postInvalidate()
            }
    }

    private fun findFirstVerticalLayout(view: ViewGroup): LinearLayout? {
        if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) return view
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is ViewGroup) {
                val res = findFirstVerticalLayout(child)
                if (res != null) return res
            }
        }
        return null
    }

    override fun stop(context: Context?) {
        patcher.unpatchAll()
        translatedMessages.clear()
        currentChatAdapter = null
    }

    private fun requestTranslation(text: String, from: String? = null, to: String? = null): TranslateResult {
        val rawUrl = settings.getString("apiUrl", "https://api.openai.com/v1").trimEnd('/')
        val apiKey = settings.getString("apiKey", "").trim()
        val model = settings.getString("model", "gpt-4o-mini").trim()
        val toLang = if (!to.isNullOrBlank()) to else settings.getString("defaultLanguage", "中文")
        val fromLang = if (!from.isNullOrBlank()) from else "自动识别"

        if (apiKey.isEmpty()) {
            return TranslateError(401, "请先在插件设置中填写 API Key")
        }

        return try {
            val apiUrl = if (rawUrl.endsWith("/v1")) "$rawUrl/chat/completions" else "$rawUrl/v1/chat/completions"
            val payload = OpenAIChatRequest(
                model = model,
                messages = listOf(
                    OpenAIMessage("system", "You are a professional translator. Directly translate to '$toLang'. Only output translation."),
                    OpenAIMessage("user", text)
                )
            )

            val req = Http.Request(apiUrl, "POST")
                .setHeader("Authorization", "Bearer $apiKey")
                .setHeader("Content-Type", "application/json")
                .executeWithBody(GsonUtils.toJson(payload))

            if (!req.ok()) return TranslateError(req.statusCode, "API 报错: ${req.text()}")

            val resp = GsonUtils.fromJson(req.text(), OpenAIChatResponse::class.java)
            val messageObj = resp?.choices?.firstOrNull()?.message
            val content = messageObj?.content?.ifEmpty { messageObj.reasoning_content } ?: ""

            if (content.isBlank()) return TranslateError(500, "翻译结果为空")

            TranslateSuccess(fromLang, toLang, text, content.trim())
        } catch (e: Exception) {
            TranslateError(500, "网络或解析异常: ${e.message}")
        }
    }
}
package tech.tyman.plugins.translate

import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Button
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        setActionBarTitle("AI Translate")
        val ctx = requireContext()

        // 1. API 接口地址输入框
        val urlInput = TextInput(ctx, "API URL (Base URL)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("apiUrl", "https://api.openai.com/v1/chat/completions"))
        }

        // 2. API Key 输入框
        val keyInput = TextInput(ctx, "API Key (sk-...)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("apiKey", ""))
        }

        // 3. 模型名称输入框
        val modelInput = TextInput(ctx, "Model Name (如 gpt-4o-mini / deepseek-chat)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("model", "gpt-4o-mini"))
        }

        // 4. 默认目标语言
        val langInput = TextInput(ctx, "默认翻译目标语言 (如 zh, en, ja)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("defaultLanguage", "zh"))
        }

        // 保存按钮
        val saveButton = Button(ctx).apply {
            text = "保存配置"
            setOnClickListener {
                settings.setString("apiUrl", urlInput.editText.text.toString().trim())
                settings.setString("apiKey", keyInput.editText.text.toString().trim())
                settings.setString("model", modelInput.editText.text.toString().trim())
                settings.setString("defaultLanguage", langInput.editText.text.toString().trim())
                Utils.showToast("已保存 AI 翻译设置！")
                close()
            }
        }

        val tipText = TextView(ctx).apply {
            text = "\n提示：\n1. 支持任意兼容 OpenAI 格式的 API（如 DeepSeek、OpenAI、各类中转/OneAPI）。\n2. 语言代码建议填写 zh (简体中文)、en (英文)、ja (日文) 等。"
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorOnPrimary))
        }

        addView(urlInput)
        addView(keyInput)
        addView(modelInput)
        addView(langInput)
        addView(saveButton)
        addView(tipText)
    }
}
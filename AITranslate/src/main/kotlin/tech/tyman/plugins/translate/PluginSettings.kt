package com.lishangaaa.plugins.aitranslate

import android.app.AlertDialog
import android.view.View
import android.widget.TextView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.GsonUtils
import com.aliucord.views.Button
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        setActionBarTitle("AI 翻译设置")
        val ctx = requireContext()

        val urlInput = TextInput(ctx, "API 接口地址 (Base URL)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("apiUrl", "https://api.openai.com/v1"))
        }

        val keyInput = TextInput(ctx, "API Key (密钥)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("apiKey", ""))
        }

        val modelInput = TextInput(ctx, "模型名称 (Model)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("model", "gpt-4o-mini"))
        }

        val langInput = TextInput(ctx, "默认翻译目标语言").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("defaultLanguage", "中文"))
        }

        val fetchModelsButton = Button(ctx).apply {
            text = "获取模型列表"
            setOnClickListener {
                val rawUrl = urlInput.editText.text.toString().trim().trimEnd('/')
                val apiKey = keyInput.editText.text.toString().trim()

                if (apiKey.isEmpty()) {
                    Utils.showToast("请先填写 API Key！", true)
                    return@setOnClickListener
                }

                Utils.showToast("正在拉取模型...")
                Utils.threadPool.execute {
                    try {
                        val modelsUrl = if (rawUrl.endsWith("/v1")) "$rawUrl/models" else "$rawUrl/v1/models"
                        val res = Http.Request(modelsUrl, "GET")
                            .setHeader("Authorization", "Bearer $apiKey")
                            .execute()

                        if (!res.ok()) {
                            Utils.mainThread.post { Utils.showToast("获取失败 (${res.statusCode})", true) }
                            return@execute
                        }

                        val modelResp = GsonUtils.fromJson(res.text(), OpenAIModelsResponse::class.java)
                        val modelNames = modelResp?.data?.map { it.id }?.sorted() ?: emptyList()

                        Utils.mainThread.post {
                            val act = activity ?: Utils.appActivity ?: return@post
                            if (modelNames.isEmpty()) {
                                Utils.showToast("未找到可用模型", true)
                                return@post
                            }
                            AlertDialog.Builder(act)
                                .setTitle("选择模型 (${modelNames.size} 个)")
                                .setItems(modelNames.toTypedArray()) { _, which ->
                                    modelInput.editText.setText(modelNames[which])
                                    Utils.showToast("已选择: ${modelNames[which]}")
                                }
                                .show()
                        }
                    } catch (e: Exception) {
                        Utils.mainThread.post { Utils.showToast("请求出错: ${e.message}", true) }
                    }
                }
            }
        }

        val testButton = Button(ctx).apply {
            text = "测试连接"
            setOnClickListener {
                val rawUrl = urlInput.editText.text.toString().trim().trimEnd('/')
                val apiKey = keyInput.editText.text.toString().trim()
                val model = modelInput.editText.text.toString().trim()

                if (apiKey.isEmpty()) {
                    Utils.showToast("请先填写 API Key！", true)
                    return@setOnClickListener
                }

                Utils.showToast("正在测试连通性...")
                Utils.threadPool.execute {
                    val start = System.currentTimeMillis()
                    val chatUrl = if (rawUrl.endsWith("/v1")) "$rawUrl/chat/completions" else "$rawUrl/v1/chat/completions"
                    try {
                        val testPayload = OpenAIChatRequest(
                            model = model,
                            messages = listOf(OpenAIMessage("user", "hi")),
                            max_tokens = 5
                        )

                        val res = Http.Request(chatUrl, "POST")
                            .setHeader("Authorization", "Bearer $apiKey")
                            .setHeader("Content-Type", "application/json")
                            .executeWithBody(GsonUtils.toJson(testPayload))

                        val cost = System.currentTimeMillis() - start
                        Utils.mainThread.post {
                            if (res.ok()) {
                                Utils.showToast("连接正常！耗时: ${cost}ms")
                            } else {
                                Utils.showToast("连接失败 (${res.statusCode})", true)
                            }
                        }
                    } catch (e: Exception) {
                        Utils.mainThread.post { Utils.showToast("网络异常: ${e.message}", true) }
                    }
                }
            }
        }

        val saveButton = Button(ctx).apply {
            text = "保存配置"
            setOnClickListener {
                settings.setString("apiUrl", urlInput.editText.text.toString().trim())
                settings.setString("apiKey", keyInput.editText.text.toString().trim())
                settings.setString("model", modelInput.editText.text.toString().trim())
                settings.setString("defaultLanguage", langInput.editText.text.toString().trim())
                Utils.showToast("设置已保存！")
                close()
            }
        }

        val tipText = TextView(ctx).apply {
            text = "\n💡 支持 OpenAI 格式中转站、DeepSeek、Claude 等。"
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorOnPrimary))
        }

        addView(urlInput)
        addView(keyInput)
        addView(modelInput)
        addView(langInput)
        addView(fetchModelsButton)
        addView(testButton)
        addView(saveButton)
        addView(tipText)
    }
}
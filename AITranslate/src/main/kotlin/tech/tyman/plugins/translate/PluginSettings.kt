package com.lishangaaa.plugins.aitranslate

import android.app.AlertDialog
import android.view.View
import android.widget.TextView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Button
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R
import org.json.JSONObject

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        setActionBarTitle("AI 翻译设置 (AI Translate)")
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

        val langInput = TextInput(ctx, "翻译目标 (如: 中文, English, 日文, 东北话, 梗体中文)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("defaultLanguage", "中文"))
        }

        val fetchModelsButton = Button(ctx).apply {
            text = "获取模型列表 (Fetch Models)"
            setOnClickListener {
                val rawUrl = urlInput.editText.text.toString().trim()
                val apiKey = keyInput.editText.text.toString().trim()

                if (apiKey.isEmpty()) {
                    Utils.showToast("请先填写 API Key！", true)
                    return@setOnClickListener
                }

                Utils.showToast("正在拉取模型...")
                Utils.threadPool.execute {
                    try {
                        val modelsUrl = formatModelsUrl(rawUrl)
                        val res = Http.Request(modelsUrl, "GET")
                            .setHeader("Authorization", "Bearer $apiKey")
                            .execute()

                        if (!res.ok()) {
                            Utils.mainThread.post {
                                Utils.showToast("获取失败 (${res.statusCode})", true)
                            }
                            return@execute
                        }

                        val json = JSONObject(res.text())
                        val dataArray = json.getJSONArray("data")
                        val modelNames = ArrayList<String>()
                        for (i in 0 until dataArray.length()) {
                            modelNames.add(dataArray.getJSONObject(i).getString("id"))
                        }
                        modelNames.sort()

                        Utils.mainThread.post {
                            if (modelNames.isEmpty()) {
                                Utils.showToast("未找到可用模型")
                                return@post
                            }
                            AlertDialog.Builder(ctx)
                                .setTitle("选择模型 (共 ${modelNames.size} 个)")
                                .setItems(modelNames.toTypedArray()) { _, which ->
                                    val selected = modelNames[which]
                                    modelInput.editText.setText(selected)
                                    Utils.showToast("已选择: $selected")
                                }
                                .show()
                        }
                    } catch (e: Exception) {
                        Utils.mainThread.post {
                            Utils.showToast("请求出错: ${e.localizedMessage ?: e.message}", true)
                        }
                    }
                }
            }
        }

        val testButton = Button(ctx).apply {
            text = "测试连接 (Test Connection)"
            setOnClickListener {
                val rawUrl = urlInput.editText.text.toString().trim()
                val apiKey = keyInput.editText.text.toString().trim()
                val model = modelInput.editText.text.toString().trim()

                if (apiKey.isEmpty()) {
                    Utils.showToast("请先填写 API Key！", true)
                    return@setOnClickListener
                }

                Utils.showToast("正在测试连通性...")
                Utils.threadPool.execute {
                    val start = System.currentTimeMillis()
                    val chatUrl = formatChatUrl(rawUrl)
                    try {
                        val json = JSONObject().apply {
                            put("model", model)
                            put("messages", org.json.JSONArray().apply {
                                put(JSONObject().put("role", "user").put("content", "hi"))
                            })
                            put("max_tokens", 5)
                        }

                        val res = Http.Request(chatUrl, "POST")
                            .setHeader("Authorization", "Bearer $apiKey")
                            .setHeader("Content-Type", "application/json")
                            .executeWithBody(json.toString())

                        val cost = System.currentTimeMillis() - start
                        Utils.mainThread.post {
                            if (res.ok()) {
                                Utils.showToast("连接正常！响应耗时: ${cost}ms")
                            } else {
                                Utils.showToast("连接失败 (${res.statusCode})", true)
                            }
                        }
                    } catch (e: Exception) {
                        Utils.mainThread.post {
                            Utils.showToast("网络连接异常: ${e.localizedMessage ?: e.message}", true)
                        }
                    }
                }
            }
        }

        val saveButton = Button(ctx).apply {
            text = "保存配置 (Save)"
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
            text = "\n💡 小提示：\n• 支持所有兼容 OpenAI 格式的中转站及 DeepSeek、Claude、通义千问等模型。\n• 翻译目标可自由发挥（例如输入「幽默接地气的口语」、「日文」或「粤语」）。"
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

    private fun formatChatUrl(baseUrl: String): String {
        val url = baseUrl.trimEnd('/')
        return when {
            url.endsWith("/chat/completions") -> url
            url.endsWith("/v1") -> "$url/chat/completions"
            else -> "$url/v1/chat/completions"
        }
    }

    private fun formatModelsUrl(baseUrl: String): String {
        var url = baseUrl.trimEnd('/')
        if (url.endsWith("/chat/completions")) {
            url = url.substringBeforeLast("/chat/completions")
        }
        return if (url.endsWith("/v1")) "$url/models" else "$url/v1/models"
    }
}
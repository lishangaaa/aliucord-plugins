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
        setActionBarTitle("AI Translate 设置")
        val ctx = requireContext()

        // 1. Base URL
        val urlInput = TextInput(ctx, "Base URL (支持根路径或完整 endpoint)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("apiUrl", "https://api.openai.com/v1"))
        }

        // 2. API Key
        val keyInput = TextInput(ctx, "API Key (sk-...)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("apiKey", ""))
        }

        // 3. 模型名称
        val modelInput = TextInput(ctx, "Model Name (如 gpt-4o-mini, deepseek-chat)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("model", "gpt-4o-mini"))
        }

        // 4. 默认目标语言（支持任意自然语言风格描述）
        val langInput = TextInput(ctx, "默认翻译目标 (如 中文, 英语, 日文, 文言文, 猫娘语气)").apply {
            editText.maxLines = 1
            editText.setText(settings.getString("defaultLanguage", "中文"))
        }

        // 按钮：获取模型列表
        val fetchModelsButton = Button(ctx).apply {
            text = "获取模型列表"
            setOnClickListener {
                val rawUrl = urlInput.editText.text.toString().trim()
                val apiKey = keyInput.editText.text.toString().trim()

                if (apiKey.isEmpty()) {
                    Utils.showToast("请先输入 API Key！", true)
                    return@setOnClickListener
                }

                Utils.showToast("正在拉取模型列表...")
                Utils.threadPool.execute {
                    try {
                        val modelsUrl = formatModelsUrl(rawUrl)
                        val res = Http.Request(modelsUrl, "GET")
                            .setHeader("Authorization", "Bearer $apiKey")
                            .execute()

                        if (!res.ok()) {
                            Utils.mainThread.post {
                                Utils.showToast("获取失败 (${res.statusCode}): ${res.text()}", true)
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
                                Utils.showToast("未拉取到任何模型")
                                return@post
                            }
                            AlertDialog.Builder(ctx)
                                .setTitle("选择模型 (${modelNames.size} 个)")
                                .setItems(modelNames.toTypedArray()) { _, which ->
                                    val selected = modelNames[which]
                                    modelInput.editText.setText(selected)
                                    Utils.showToast("已选择: $selected")
                                }
                                .show()
                        }
                    } catch (e: Exception) {
                        Utils.mainThread.post {
                            Utils.showToast("请求异常: ${e.localizedMessage ?: e.message}", true)
                        }
                    }
                }
            }
        }

        // 按钮：测试连接
        val testButton = Button(ctx).apply {
            text = "测试连接"
            setOnClickListener {
                val rawUrl = urlInput.editText.text.toString().trim()
                val apiKey = keyInput.editText.text.toString().trim()
                val model = modelInput.editText.text.toString().trim()

                if (apiKey.isEmpty()) {
                    Utils.showToast("请先输入 API Key！", true)
                    return@setOnClickListener
                }

                Utils.showToast("正在测试连接...")
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
                                Utils.showToast("连接成功！延迟: ${cost}ms")
                            } else {
                                Utils.showToast("连接失败 (${res.statusCode}): ${res.text()}", true)
                            }
                        }
                    } catch (e: Exception) {
                        Utils.mainThread.post {
                            Utils.showToast("连接异常: ${e.localizedMessage ?: e.message}", true)
                        }
                    }
                }
            }
        }

        // 按钮：保存配置
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
            text = "\n使用提示：\n1. 目标语言直接支持自然语言（例如“中文”、“文言文”、“接地气的口语”、“猫娘语气”等）。\n2. 填完 Key 可点击【获取模型列表】快捷选择模型。\n3. 点击【测试连接】可验证 API 与网络连通性。"
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
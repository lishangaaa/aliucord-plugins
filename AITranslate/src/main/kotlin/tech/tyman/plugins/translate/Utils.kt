package com.lishangaaa.plugins.aitranslate

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.discord.models.message.Message

fun refreshAdapterForMessage(messageId: Long) {
    Utils.mainThread.post {
        try {
            val activity = Utils.appActivity ?: return@post
            val root = activity.window.decorView
            val list = ArrayList<RecyclerView>()
            findRecyclerViews(root, list)
            for (rv in list) {
                // 如果当前 RecyclerView 正在计算布局，跳过避免崩溃
                if (rv.isComputingLayout) continue
                val adapter = rv.adapter ?: continue
                val fields = adapter.javaClass.declaredFields
                for (field in fields) {
                    field.isAccessible = true
                    val value = field.get(adapter)
                    if (value is List<*>) {
                        for (i in value.indices) {
                            val item = value[i] ?: continue
                            val itemFields = item.javaClass.declaredFields
                            for (itemField in itemFields) {
                                if (Message::class.java.isAssignableFrom(itemField.type) || itemField.name.equals("message", ignoreCase = true)) {
                                    itemField.isAccessible = true
                                    val msg = itemField.get(item) as? Message
                                    if (msg?.id == messageId) {
                                        adapter.notifyItemChanged(i)
                                        return@post // 命中并更新后直接退出，避免额外遍历
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }
}

private fun findRecyclerViews(view: View, outList: MutableList<RecyclerView>) {
    if (view is RecyclerView) {
        outList.add(view)
    }
    if (view is ViewGroup) {
        var i = 0
        val count = view.childCount
        while (i < count) {
            findRecyclerViews(view.getChildAt(i), outList)
            i++
        }
    }
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
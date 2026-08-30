package com.lishangaaa.plugins.aitranslate

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.discord.models.message.Message
import java.lang.reflect.Field

fun refreshAdapterForMessage(messageId: Long) {
    Utils.mainThread.post {
        try {
            val activity = Utils.appActivity ?: return@post
            val root = activity.window.decorView
            val list = ArrayList<RecyclerView>()
            findRecyclerViews(root, list)
            for (rv in list) {
                if (rv.isComputingLayout) continue
                val adapter = rv.adapter ?: continue
                var updated = false

                // 递归查找当前类及所有父类的字段（解决 Discord 列表存放在父类导致反射失败的问题）
                val fields = getAllFields(adapter.javaClass)
                for (field in fields) {
                    field.isAccessible = true
                    val value = try { field.get(adapter) } catch (_: Throwable) { null }
                    if (value is List<*>) {
                        for (i in value.indices) {
                            val item = value[i] ?: continue
                            val itemFields = getAllFields(item.javaClass)
                            for (itemField in itemFields) {
                                if (Message::class.java.isAssignableFrom(itemField.type) || itemField.name.equals("message", ignoreCase = true)) {
                                    itemField.isAccessible = true
                                    val msg = try { itemField.get(item) as? Message } catch (_: Throwable) { null }
                                    if (msg?.id == messageId) {
                                        adapter.notifyItemChanged(i)
                                        updated = true
                                        break
                                    }
                                }
                            }
                            if (updated) break
                        }
                    }
                    if (updated) break
                }

                // 兜底刷新：若精准匹配未命中，执行全量刷新确保必定生效
                if (!updated) {
                    adapter.notifyDataSetChanged()
                }
            }
        } catch (_: Throwable) {}
    }
}

private fun getAllFields(startClass: Class<*>): List<Field> {
    val fields = ArrayList<Field>()
    var clazz: Class<*>? = startClass
    while (clazz != null && clazz != Any::class.java) {
        try {
            fields.addAll(clazz.declaredFields)
        } catch (_: Throwable) {}
        clazz = clazz.superclass
    }
    return fields
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
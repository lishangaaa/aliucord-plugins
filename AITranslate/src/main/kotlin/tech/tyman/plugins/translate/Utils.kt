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
                val adapter = rv.adapter ?: continue
                var updated = false
                val fields = adapter.javaClass.declaredFields
                var fIdx = 0
                while (fIdx < fields.size) {
                    val field = fields[fIdx]
                    field.isAccessible = true
                    val value = field.get(adapter)
                    if (value is List<*>) {
                        var i = 0
                        while (i < value.size) {
                            val item = value[i]
                            if (item != null) {
                                val itemFields = item.javaClass.declaredFields
                                var mIdx = 0
                                while (mIdx < itemFields.size) {
                                    val itemField = itemFields[mIdx]
                                    if (Message::class.java.isAssignableFrom(itemField.type) || itemField.name.equals("message", ignoreCase = true)) {
                                        itemField.isAccessible = true
                                        val msg = itemField.get(item) as? Message
                                        if (msg?.id == messageId) {
                                            adapter.notifyItemChanged(i)
                                            updated = true
                                            break
                                        }
                                    }
                                    mIdx++
                                }
                            }
                            if (updated) break
                            i++
                        }
                    }
                    if (updated) break
                    fIdx++
                }
                if (!updated) {
                    adapter.notifyDataSetChanged()
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
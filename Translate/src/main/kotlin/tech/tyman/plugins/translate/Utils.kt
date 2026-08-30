package com.lishangaaa.plugins.aitranslate

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils

fun rerenderAllChatLists() {
    Utils.mainThread.post {
        try {
            val activity = Utils.appActivity ?: return@post
            val root = activity.window.decorView
            val list = ArrayList<RecyclerView>()
            findRecyclerViews(root, list)
            for (rv in list) {
                rv.adapter?.notifyDataSetChanged()
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
package dev.luma.agent.core

import android.content.Context
import org.json.JSONObject

/**
 * What the user was writing in each chat (text and attached files), kept when they switch to another screen or chat and
 * after the app is closed. A new, not yet created chat has its own draft. Sending clears the draft.
 */
class DraftStore(context: Context) {
    data class Draft(val text: String = "", val files: List<Attachment> = emptyList())
    private val preferences = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, Draft>()
    private fun key(chat: Long?) = chat?.toString() ?: "new"
    fun get(chat: Long?): Draft = cache.getOrPut(key(chat)) {
        runCatching {
            val json = JSONObject(preferences.getString(key(chat), null) ?: return@runCatching Draft())
            Draft(json.optString("text").take(12_000), Attachment.decode(json.optString("files", "[]")))
        }.getOrDefault(Draft())
    }
    fun save(chat: Long?, text: String, files: List<Attachment>) {
        val draft = Draft(text.take(12_000), files)
        if (cache[key(chat)] == draft) return
        cache[key(chat)] = draft
        preferences.edit().apply {
            if (draft.text.isEmpty() && draft.files.isEmpty()) remove(key(chat))
            else putString(key(chat), JSONObject().put("text", draft.text).put("files", Attachment.encode(draft.files)).toString())
        }.apply()
    }
}

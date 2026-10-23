package dev.luma.agent.core

import org.json.JSONArray
import org.json.JSONObject

/** A real tool-created file, stored in message metadata, never inferred from model prose. */
data class GeneratedFile(val name: String,val uri: String,val size: Long,val created: Long,
    val mime: String="application/octet-stream",val parentUri: String="") {
    fun json()=JSONObject().put("name",name).put("uri",uri).put("size",size).put("created",created).put("mime",mime).put("parentUri",parentUri)
    companion object {
        fun parse(j: JSONObject?): GeneratedFile? {
            if(j==null) return null
            val uri=j.optString("uri");val name=j.optString("name")
            if(name.isBlank() || !(uri.startsWith("content://") || uri.startsWith("file://"))) return null
            return GeneratedFile(name.take(200),uri,j.optLong("size").coerceAtLeast(0),j.optLong("created"),j.optString("mime","application/octet-stream"),j.optString("parentUri"))
        }
        fun decode(a: JSONArray?): List<GeneratedFile> = if(a==null) emptyList() else (0 until a.length().coerceAtMost(100)).mapNotNull {parse(a.optJSONObject(it))}
    }
}

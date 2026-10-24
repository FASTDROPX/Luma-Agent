package dev.luma.agent.core

import org.json.JSONObject
import java.util.Locale

/** Speech is independent from the selected text model; secrets live in SettingsVault. */
data class VoiceSettings(
    val recognition: String = "local", val synthesis: String = "local",
    val endpoint: String = "https://api.openai.com/v1", val key: String = "",
    val sttModel: String = "whisper-1", val ttsModel: String = "gpt-4o-mini-tts",
    val cloudVoice: String = "coral", val deviceVoice: String = "", val speed: Float = 1f,
    val wakeWords: String = "люма, эй люма, лума, эй лума, luma",
    val language: String = "ru", val outputLanguage: String = "ru", val gender: String = "female"
) {
    fun json() = JSONObject().put("recognition",recognition).put("synthesis",synthesis)
        .put("endpoint",endpoint).put("key",key).put("sttModel",sttModel).put("ttsModel",ttsModel)
        .put("cloudVoice",cloudVoice).put("deviceVoice",deviceVoice).put("speed",speed.toDouble()).put("wakeWords",wakeWords)
        .put("language",language).put("outputLanguage",outputLanguage).put("gender",gender)
    companion object {
        fun parse(j: JSONObject?): VoiceSettings {
            if(j==null) return VoiceSettings()
            val d=VoiceSettings()
            return VoiceSettings(if(j.optString("recognition")=="cloud") "cloud" else "local",j.optString("synthesis").takeIf {it in setOf("local","cloud","piper")} ?: "local",
                j.optString("endpoint",d.endpoint).take(2000),j.optString("key").take(4000),j.optString("sttModel",d.sttModel).take(200),
                j.optString("ttsModel",d.ttsModel).take(200),j.optString("cloudVoice",d.cloudVoice).take(200),j.optString("deviceVoice").take(500),
                j.optDouble("speed",1.0).toFloat().let {if(it.isFinite()) it.coerceIn(.5f,2f) else 1f},j.optString("wakeWords",d.wakeWords).take(500),
                j.optString("language","ru").takeIf {it in setOf("ru","en","zh","de","fr")} ?: "ru",
                j.optString("outputLanguage","ru").takeIf {it in setOf("ru","en","zh","de","fr")} ?: "ru",
                if(j.optString("gender")=="male") "male" else "female")
        }
    }
}

object VoiceText {
    fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('ё','е').replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
        .replace(Regex("(?<=[\\u3400-\\u9FFF])\\s+(?=[\\u3400-\\u9FFF])"),"")
    private fun prefix(text: String,wake: String)=text==wake || text.startsWith("$wake ") || (wake.any {it in '\u3400'..'\u9FFF'} && text.startsWith(wake))
    /** Whole words at the beginning only: mentioning Luma inside a sentence isn't activation. */
    fun afterWake(text: String, aliases: String): String? {
        val normalized=normalize(text)
        val wake=aliases.split(',').map(::normalize).filter {it.isNotBlank()}.sortedByDescending {it.length}
            .firstOrNull {prefix(normalized,it)} ?: return null
        return normalized.removePrefix(wake).trim()
    }
    fun stop(text: String) = normalize(text) in setOf("стоп разговор","заверши разговор","закончи разговор","хватит","до свидания","люма стоп","лума стоп",
        "stop conversation","end conversation","goodbye","beende das gespräch","gespräch beenden","au revoir","termine la conversation","结束对话","停止对话","结束 对话","停止 对话")
    // «Люма» isn't in Vosk's Russian dictionary; its two syllables are. A constrained
    // recognizer detects the name while a second recognizer preserves the whole command.
    fun wakeGrammar(aliases: String,language: String="ru")=aliases.split(',').map {normalize(it).let {name->if(language=="ru") name.replace("люма","лю ма").replace("лума","лу ма").replace("luma","лу ма") else name}}.filter {it.isNotBlank()}.distinct()
    fun wakeEnd(result: JSONObject,aliases: String,language: String="ru"): Float? {
        val text=result.optString("text",result.optString("partial"))
        val grammar=wakeGrammar(aliases,language)
        val matchedPrefix=grammar.sortedByDescending {it.length}.firstOrNull {prefix(normalize(text),it)} ?: return null
        val words=result.optJSONArray("result") ?: result.optJSONArray("partial_result") ?: return null
        val endIndex=if(language=="zh") {
            var accumulated="";var index=-1
            for(i in 0 until words.length()) {accumulated+=normalize(words.optJSONObject(i)?.optString("word").orEmpty());if(accumulated==matchedPrefix) {index=i;break}}
            index
        } else matchedPrefix.split(' ').size-1
        return words.optJSONObject(endIndex)?.optDouble("end")?.toFloat()?.takeIf {it.isFinite() && it>0}
    }
    fun afterWakeTime(result: JSONObject,end: Float): String {
        val words=result.optJSONArray("result") ?: return ""
        return (0 until words.length()).mapNotNull {words.optJSONObject(it)}.filter {it.optDouble("start")>=end-.04f}
            .joinToString(" ") {it.optString("word")}
    }
    fun spoken(text: String,language: String="ru"): String = text.replace(Regex("```[\\s\\S]*?```")," "+when(language) {"en"->"Code is in the message.";"zh"->"代码见消息。";"de"->"Der Code steht in der Nachricht.";"fr"->"Le code figure dans le message.";else->"Код в сообщении."}+" ")
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"),"").replace(Regex("\\[([^]]+)]\\([^)]*\\)"),"$1")
        .replace(Regex("https?://\\S+"),when(language) {"en"->"link";"zh"->"链接";"de"->"Link";"fr"->"lien";else->"ссылка"}).replace(Regex("[*_#`>|]"),"").trim()
    fun chunks(text: String, max: Int = 3000,language: String="ru"): List<String> {
        var remaining=spoken(text,language);val result=mutableListOf<String>()
        while(remaining.isNotEmpty()) {
            val end=if(remaining.length<=max) remaining.length else remaining.lastIndexOf(' ',max).takeIf {it>max/2} ?: max
            result+=remaining.take(end);remaining=remaining.drop(end).trimStart()
        }
        return result
    }
}

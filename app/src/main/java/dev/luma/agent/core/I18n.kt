package dev.luma.agent.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/** Bundled UI catalogues only. Prompts, user messages and model replies are never machine-translated at runtime. */
object I18n {
    var language by mutableStateOf("ru");private set
    private var catalogues=emptyMap<String,Map<String,String>>()
    private var patterns=emptyList<Pair<Regex,Pair<String,String>>>()
    private val cache=object: LinkedHashMap<String,String>(128,.75f,true) {override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,String>?)=size>512}
    fun initialize(context: Context,choice: String) {
        if(catalogues.isEmpty()) catalogues=listOf("en","zh","de","fr").associateWith {code ->
            runCatching {val j=JSONObject(context.assets.open("i18n/$code.json").bufferedReader().use {it.readText()})
                j.keys().asSequence().associateWith {j.getString(it)}}.getOrDefault(emptyMap())
        }
        choose(choice)
    }
    @Synchronized fun choose(choice: String,system: String=Locale.getDefault().language) {
        val code=(if(choice=="system") system else choice).takeIf {it in setOf("ru","en","zh","de","fr")} ?: "en"
        if(language==code && patterns.isNotEmpty()) return
        language=code;cache.clear()
        patterns=catalogues[code].orEmpty().filterKeys {Regex("\\{\\d+\\}").containsMatchIn(it)}.map {(key,value) ->
            val parts=Regex("\\{\\d+\\}").split(key)
            Regex(parts.joinToString("([\\s\\S]*?)") {Regex.escape(it)}) to (key to value)
        }.sortedByDescending {it.second.first.length}
    }
    @Synchronized fun text(key: String,vararg args: Any?): String {
        val code=language
        var result=if(code=="ru") key else catalogues[code]?.get(key) ?: if(args.isEmpty()) cache[key] ?: run {
            val match=patterns.firstNotNullOfOrNull {(regex,source)->regex.matchEntire(key)?.let {m ->
                var translated=source.second
                Regex("\\{(\\d+)\\}").findAll(source.first).forEachIndexed {index,token ->translated=translated.replace(token.value,m.groupValues[index+1])}
                translated
            }} ?: key
            cache[key]=match;match
        } else key
        args.forEachIndexed {index,value ->result=result.replace("{$index}",value?.toString().orEmpty())}
        return result
    }
}
fun tr(key: String,vararg args: Any?)=I18n.text(key,*args)

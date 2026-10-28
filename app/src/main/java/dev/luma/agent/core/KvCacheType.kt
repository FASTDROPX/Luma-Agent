package dev.luma.agent.core

/** Stable JNI codes, independent of ggml's enum numbering; old settings retain F16. */
enum class KvCacheType(val id: String,val nativeCode: Int) {
    F16("f16",0), Q8_0("q8_0",1), Q5_0("q5_0",2), Q4_0("q4_0",3), IQ4_NL("iq4_nl",4);
    companion object {
        fun parse(value: String)=entries.firstOrNull {it.id==value} ?: F16
    }
}

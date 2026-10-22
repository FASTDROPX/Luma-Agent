package dev.luma.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.*

/** Only inserted glyphs fade. Text, IME composition, selection and offsets are never delayed. */
internal fun insertedRange(before: String, after: String): IntRange? {
    if(after.length <= before.length) return null
    var start=0
    while(start < before.length && start < after.length && before[start] == after[start]) start++
    var end=after.length;var oldEnd=before.length
    while(oldEnd > start && end > start && before[oldEnd-1] == after[end-1]) {oldEnd--;end--}
    // Include a full surrogate pair if insertion/replacement touches its boundary.
    if(start > 0 && start < after.length && after[start].isLowSurrogate()) start--
    if(end < after.length && end > 0 && after[end-1].isHighSurrogate()) end++
    return if(end > start) start until end else null
}
internal class GlyphFade(private val range: IntRange?, private val alpha: Float, private val color: androidx.compose.ui.graphics.Color): VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val result=AnnotatedString.Builder(text).apply {
            range?.let { if(it.first >= 0 && it.last < text.length) addStyle(SpanStyle(color=color.copy(alpha=alpha)),it.first,it.last+1) }
        }.toAnnotatedString()
        return TransformedText(result,OffsetMapping.Identity)
    }
}
internal class TypingFades {
    data class Part(var start: Int,var end: Int,var age: Float=0f)
    var previous="";private set
    val parts=mutableListOf<Part>()
    fun edit(text: String,enabled: Boolean) {
        if(text==previous) {if(!enabled) parts.clear();return}
        val range=insertedRange(previous,text)
        if(!enabled || range==null) parts.clear() else {
            val delta=text.length-previous.length
            parts.removeAll {it.end>range.first && it.start<range.first+1}
            parts.forEach {if(it.start>=range.first) {it.start+=delta;it.end+=delta}}
            parts+=Part(range.first,range.last+1)
            while(parts.size>24) parts.removeAt(0)
        }
        previous=text
    }
    fun advance(seconds: Float) {parts.forEach {it.age+=seconds};parts.removeAll {it.age>=.24f}}
}
@Composable fun smoothInput(value: TextFieldValue): VisualTransformation {
    return VisualTransformation.None
}

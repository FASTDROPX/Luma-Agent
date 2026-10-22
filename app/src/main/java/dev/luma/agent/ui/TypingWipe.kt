package dev.luma.agent.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue

internal class TypingWipe {
    val inserts=TypingFades()
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var frame by mutableIntStateOf(0)
    var graphemes=GraphemeMap("")
    var selection=0
    private var scrollY=0f
    val modifier: Modifier get()=Modifier.drawWithContent {
        frame
        val textLayout=layout
        if(textLayout==null || inserts.parts.isEmpty() || textLayout.layoutInput.text.text!=inserts.previous) {drawContent();return@drawWithContent}
        val caret=textLayout.getCursorRect(selection.coerceIn(0,inserts.previous.length))
        val maximum=(textLayout.size.height-size.height).coerceAtLeast(0f)
        scrollY=scrollY.coerceIn((caret.bottom-size.height).coerceAtLeast(0f),caret.top.coerceAtLeast(0f)).coerceIn(0f,maximum)
        val hidden=Path()
        for(part in inserts.parts) {
            val progress=(part.age/.24f).coerceIn(0f,1f)
            var i=graphemes.previous(part.start)
            while(i<part.end && i<inserts.previous.length) {
                val end=graphemes.next(i+1)
                var box=textLayout.getBoundingBox(i)
                for(j in i+1 until end) {val b=textLayout.getBoundingBox(j);box=Rect(minOf(box.left,b.left),minOf(box.top,b.top),maxOf(box.right,b.right),maxOf(box.bottom,b.bottom))}
                hidden.addRect(Rect(box.left+box.width*progress,box.top-scrollY,box.right,box.bottom-scrollY))
                i=end
            }
        }
        clipPath(hidden,ClipOp.Difference) {this@drawWithContent.drawContent()}
    }
}
@Composable internal fun typingWipe(value: TextFieldValue): TypingWipe {
    val enabled=LocalVisualSettings.current.smoothInput && !LocalReducedMotion.current
    val wipe=remember {TypingWipe().apply {inserts.edit(value.text,false);graphemes=GraphemeMap(value.text)}}
    remember(value.text,enabled) {wipe.inserts.edit(value.text,enabled);wipe.graphemes=GraphemeMap(value.text);value.text}
    wipe.selection=value.selection.end
    LaunchedEffect(value.text,enabled) {
        var last=0L
        while(enabled && wipe.inserts.parts.isNotEmpty()) withFrameNanos {now ->
            if(last!=0L) wipe.inserts.advance(((now-last)/1_000_000_000f).coerceAtMost(.05f))
            last=now;wipe.frame++
        }
    }
    return wipe
}

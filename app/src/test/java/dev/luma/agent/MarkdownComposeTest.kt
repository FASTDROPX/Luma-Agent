package dev.luma.agent

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.luma.agent.ui.MarkdownContent
import dev.luma.agent.ui.MarkdownView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownComposeTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun markdown(view: View): List<MarkdownView> = when(view) {
        is MarkdownView->listOf(view)
        is ViewGroup->(0 until view.childCount).flatMap {markdown(view.getChildAt(it))}
        else->emptyList()
    }
    @Test fun updatingReplyRendersAllLinesWithoutScrollingOrRecreatingItsView() {
        var source by mutableStateOf("First line")
        var narrow by mutableStateOf(false)
        compose.setContent {MaterialTheme {Box(Modifier.width(if(narrow) 180.dp else 320.dp)) {MarkdownContent(source)}}}
        fun view()=markdown(compose.activity.window.decorView).singleOrNull()
        compose.waitUntil(10000) {view()?.text?.toString()=="First line"}
        val original=view()!!
        for(count in listOf(3,7,12)) {
            val next=(1..count).joinToString("\n\n") {"Paragraph $it has enough words to wrap across lines."}
            compose.runOnIdle {source=next}
            compose.waitUntil(10000) {view()?.text?.toString()==next && view()!!.height>=view()!!.layout.height}
            compose.runOnIdle {assertSame(original,view());assertTrue(view()!!.lineCount>=count)}
        }
        var wideHeight=0
        compose.runOnIdle {wideHeight=view()!!.height;narrow=true}
        compose.waitUntil(10000) {view()!!.width<=180 && view()!!.height>wideHeight}
    }
}

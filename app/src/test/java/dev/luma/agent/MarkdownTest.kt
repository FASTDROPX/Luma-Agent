package dev.luma.agent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownTest {
    @Test fun replyWipesFromLeftWithoutReducingGlyphOpacity() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val engine=MarkdownEngine.create(context,true,40f)
        val view=MarkdownView(context).apply {layoutParams=android.view.ViewGroup.LayoutParams(350,-2);textSize=40f;setTextColor(android.graphics.Color.WHITE)}
        view.display(engine,engine.toMarkdown("MMMM"),"wipe",false);view.setReveal(.125f)
        view.measure(View.MeasureSpec.makeMeasureSpec(350,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,350,view.measuredHeight)
        val bitmap=Bitmap.createBitmap(350,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val pixels=IntArray(350*view.height);bitmap.getPixels(pixels,0,350,0,0,350,view.height)
        assertTrue(pixels.maxOf {android.graphics.Color.alpha(it)}>245)
        assertTrue((70 until 350).all {x ->(0 until view.height).all {y ->android.graphics.Color.alpha(bitmap.getPixel(x,y))==0}})
        view.dispose()
    }
    private val sample="""
# Заголовок

**Жирный**, *курсив*, ~~зачёркнутый~~, `inline()` и [ссылка](https://example.com).

> Цитата
> в две строки.

1. Первый
2. Второй
   - Вложенный

- [x] Готово
- [ ] Следующий шаг

| Имя | Значение |
|:---|---:|
| Luma | 42 |

---

```kotlin
fun hello() = "Luma"
```

[ref]: https://example.com

[Ссылка по имени][ref]
""".trimIndent()
    @Test fun commonMarkAndGfmProduceRealSpansWithoutLiteralFormattingMarkers() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val engine=MarkdownEngine.create(context,true,18f);val rich=engine.toMarkdown(sample)
        assertTrue(rich.toString().contains("Заголовок"));assertFalse(rich.toString().contains("**Жирный**"))
        assertFalse(rich.toString().contains("```"));assertFalse(rich.toString().contains("~~зачёркнутый~~"))
        val types=rich.getSpans(0,rich.length,Any::class.java).map {it.javaClass.simpleName}
        for(type in listOf("HeadingSpan","StrongEmphasisSpan","EmphasisSpan","StrikethroughSpan","BlockQuoteSpan","TableRowSpan","TaskListSpan","CodeBlockSpan","LinkSpan")) assertTrue("Missing $type: $types",types.any {type in it})
    }
    @Test fun replyRevealsItsLaidOutLinesWithoutReparsingOrBreakingEmoji() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val engine=MarkdownEngine.create(context,true,18f);val view=MarkdownView(context).apply {layoutParams=android.view.ViewGroup.LayoutParams(350,android.view.ViewGroup.LayoutParams.WRAP_CONTENT)}
        val rich=engine.toMarkdown(sample);view.setTextColor(0xffe8edf8.toInt());view.display(engine,rich,"sample",false)
        fun measure() {view.measure(View.MeasureSpec.makeMeasureSpec(350,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,view.measuredWidth,view.measuredHeight)}
        measure();val full=view.height
        view.setReveal(.12f);measure();assertTrue(view.height<full);assertEquals(rich.toString(),view.text.toString())
        view.setReveal(1f);measure();assertEquals(full,view.height)
        val bitmap=Bitmap.createBitmap(390,full+32,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(0xff10131b.toInt());canvas.translate(20f,16f);view.draw(canvas)
        val dir=File("build/previews").apply {mkdirs()};File(dir,"30-markdown.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertEquals(3,revealBoundary("a🌙b",2));view.dispose()
    }
    @Test fun formulasAreNormalizedOnlyOutsideCodeAndUnsafeLinksStayInactive() {
        val text="Формула \\(x^2\\), код `\\(x\\)` и \\[y=2\\]"
        val normalized=normalizeMath(text)
        assertTrue(normalized.contains("\$\$x^2\$\$"));assertTrue(normalized.contains("`\\(x\\)`"))
        assertTrue(normalized.contains("\n\$\$\ny=2\n\$\$"))
        assertFalse(MarkdownEngine.safeLink("javascript:alert(1)"));assertFalse(MarkdownEngine.safeLink("intent://x"));assertFalse(MarkdownEngine.safeLink("file:///data/data/private"))
        assertTrue(MarkdownEngine.safeLink("https://example.com"))
    }
    @Test fun remoteImagesBecomeTapToOpenLinksAndAreNeverLoaded() {
        val engine=MarkdownEngine.create(ApplicationProvider.getApplicationContext<Application>(),true,18f)
        val rich=engine.toMarkdown("Смотри ![кот](https://example.com/c.png?d=secret) и ![](https://example.com/x.png)")
        assertTrue(rich.toString().contains("🖼 кот"));assertTrue(rich.toString().contains("🖼 изображение"))
        val links=rich.getSpans(0,rich.length,io.noties.markwon.core.spans.LinkSpan::class.java).sortedBy {rich.getSpanStart(it)}.map {it.link}
        assertEquals(listOf("https://example.com/c.png?d=secret","https://example.com/x.png"),links)
        assertEquals(0,rich.getSpans(0,rich.length,io.noties.markwon.image.AsyncDrawableSpan::class.java).size)
    }
    @Test fun replyRevealNeverSplitsEmojiFamiliesOrCombiningCharacters() {
        val family="👩‍👩‍👧‍👦"
        val text="a"+family+"e\u0301"
        val map=GraphemeMap(text)
        for(i in 2 until family.length+1) assertEquals(family.length+1,map.next(i))
        assertEquals(text.length,map.next(text.length-1))
    }
    @Test fun latexFormulaRendersWithTheBundledFontAssets() {
        MarkdownEngine.create(ApplicationProvider.getApplicationContext<Application>(),true,24f)
        val drawable=ru.noties.jlatexmath.JLatexMathDrawable.builder("\\frac{a^2+b^2}{2}").textSize(24f).color(android.graphics.Color.WHITE).build()
        assertTrue(drawable.bounds.width()>0 && drawable.bounds.height()>0)
        val bitmap=Bitmap.createBitmap(drawable.bounds.width(),drawable.bounds.height(),Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        assertTrue(pixels.any {android.graphics.Color.alpha(it)>0})
    }
    @Test fun rapidTypingRetainsOverlappingFadesAndNeverChangesTheActualText() {
        val fades=TypingFades();fades.edit("а",true);fades.advance(.12f);fades.edit("аб",true)
        assertEquals(2,fades.parts.size);assertEquals(.12f,fades.parts[0].age,.001f);assertEquals(0f,fades.parts[1].age)
        fades.edit("а🌙б",true);assertEquals("а🌙б",fades.previous)
        fades.advance(.3f);assertTrue(fades.parts.isEmpty())
        fades.edit("а",true);assertTrue(fades.parts.isEmpty())
    }
}

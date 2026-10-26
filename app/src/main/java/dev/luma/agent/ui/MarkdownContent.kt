package dev.luma.agent.ui

import dev.luma.agent.core.tr

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.*
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.*

internal object MarkdownEngine {
    private val executor=Executors.newFixedThreadPool(2) {Thread(it,"luma-markdown").apply {isDaemon=true}}
    fun safeLink(raw: String): Boolean = runCatching {val u=java.net.URI(raw);u.scheme?.lowercase() in setOf("https","http","mailto") && u.rawUserInfo==null}.getOrDefault(false)
    fun create(context: Context,palette: MarkdownPalette,textSize: Float): Markwon {
        ru.noties.jlatexmath.JLatexMathAndroid.init(context.applicationContext)
        val ink=palette.ink;val accent=palette.accent;val background=palette.code
        return Markwon.builder(context).usePlugin(StrikethroughPlugin.create()).usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context)).usePlugin(LinkifyPlugin.create()).usePlugin(HtmlPlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(textSize) {b->b.inlinesEnabled(true).executorService(executor).errorHandler {_,_->null};b.theme().textColor(ink)})
            .usePlugin(object: AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    val density=context.resources.displayMetrics.density
                    // Colours come from the app theme: accent bullets, quotes and links, soft code chips, medium headings.
                    builder.linkColor(accent).isLinkUnderlined(false).codeBackgroundColor(background).codeBlockBackgroundColor(background)
                        .codeTextColor(palette.codeInk).codeBlockTextColor(ink).codeBlockMargin((10*density).roundToInt())
                        .codeTypeface(Typeface.MONOSPACE).codeTextSize((textSize*.88f).roundToInt())
                        .headingBreakHeight(0).headingTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL))
                        .blockQuoteColor(palette.quote).blockQuoteWidth((3*density).roundToInt())
                        .thematicBreakColor(palette.rule).thematicBreakHeight((1*density).roundToInt().coerceAtLeast(1))
                        .listItemColor(accent).bulletWidth((6*density).roundToInt()).blockMargin((22*density).roundToInt())
                        .headingTextSizeMultipliers(floatArrayOf(1.45f,1.28f,1.16f,1.08f,1.03f,1f))
                }
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver {view,url ->if(safeLink(url)) runCatching {view.context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
                }
                // Remote images are never fetched automatically: a reply could encode private data in an
                // image URL. An image becomes a link that the user may open in the browser.
                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    builder.on(org.commonmark.node.Image::class.java) {visitor,image ->
                        val start=visitor.length()
                        visitor.builder().append("🖼 ")
                        val alt=visitor.length()
                        visitor.visitChildren(image)
                        if(visitor.length()==alt) visitor.builder().append(tr("изображение"))
                        CoreProps.LINK_DESTINATION.set(visitor.renderProps(),image.destination)
                        visitor.setSpansForNode(org.commonmark.node.Link::class.java,start)
                    }
                }
            }).build()
    }
}
/** Normalize the delimiters commonly returned by models, leaving code untouched. */
internal fun normalizeMath(source: String): String {
    val code=Regex("(?s)(```.*?```|~~~.*?~~~|`[^`\\n]*`)")
    fun plain(text: String)=text.replace(Regex("""(?s)\\\((.+?)\\\)""")) {"$$${it.groupValues[1]}$$"}
        .replace(Regex("""(?s)\\\[(.+?)\\\]""")) {"\n$$\n${it.groupValues[1]}\n$$\n"}
    val out=StringBuilder();var last=0
    for(match in code.findAll(source)) {out.append(plain(source.substring(last,match.range.first)));out.append(match.value);last=match.range.last+1}
    return out.append(plain(source.substring(last))).toString()
}
internal class GraphemeMap(text: String) {
    private val boundaries: IntArray
    init {
        val iterator=android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        iterator.setText(text)
        val values=mutableListOf<Int>();var offset=iterator.first()
        while(offset!=android.icu.text.BreakIterator.DONE) {values+=offset;offset=iterator.next()}
        boundaries=values.toIntArray()
    }
    fun next(index: Int): Int {
        val i=index.coerceIn(0,boundaries.last())
        val found=boundaries.binarySearch(i)
        return boundaries[if(found>=0) found else -found-1]
    }
    fun previous(index: Int): Int {
        val i=index.coerceIn(0,boundaries.last());val found=boundaries.binarySearch(i)
        return boundaries[if(found>=0) found else (-found-2).coerceAtLeast(0)]
    }
}
internal fun revealBoundary(text: CharSequence,index: Int)=GraphemeMap(text.toString()).next(index)
internal class AlphaSpan(private val opacity: Float): CharacterStyle(),UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {tp.alpha=(tp.alpha*opacity).roundToInt()}
}

/** Parse once, progressively expose its laid out lines; Markdown syntax never flashes/reflows. */
internal class MarkdownView(context: Context): TextView(context) {
    var cursor: (Float)->Unit={}
    var growth: ()->Unit={}
    private var sourceKey=""
    private var renderer: Markwon?=null
    private var parsedText: Spanned?=null
    private var full: Spannable?=null
    private var graphemes=GraphemeMap("")
    private var animator: ValueAnimator?=null
    private var fraction=1f
    private var visibleChars=0
    private var previousHeight=-1
    init {includeFontPadding=false;setLineSpacing(3*resources.displayMetrics.density,1f);setTextIsSelectable(true);movementMethod=LinkMovementMethod.getInstance();setPadding(0,0,0,2)}
    fun display(markwon: Markwon,parsed: Spanned,key: String,animate: Boolean) {
        if(sourceKey==key && renderer===markwon && parsedText===parsed && full!=null) return
        val fresh=sourceKey!=key || full==null
        if(fresh) {animator?.cancel();fraction=if(animate) 0f else 1f}
        sourceKey=key;renderer=markwon;parsedText=parsed
        markwon.setParsedMarkdown(this,parsed);full=text as? Spannable;graphemes=GraphemeMap(text.toString())
        setReveal(fraction)
        if(fresh && animate) {
            animator=ValueAnimator.ofFloat(0f,1f).apply {
                duration=(parsed.length*5L).coerceIn(800,4200);interpolator=android.view.animation.LinearInterpolator()
                addUpdateListener {setReveal(it.animatedValue as Float)};start()
            }
        }
    }
    internal fun setReveal(progress: Float) {
        fraction=progress.coerceIn(0f,1f)
        val s=full ?: return
        visibleChars=graphemes.next((s.length*fraction).roundToInt())
        requestLayout();invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val current=layout
        if(fraction>=1f || current==null || text.isEmpty()) {super.onDraw(canvas);return}
        val raw=text.length*fraction
        val start=graphemes.previous(raw.toInt());val end=graphemes.next(ceil(raw).toInt())
        val line=current.getLineForOffset(start)
        val a=current.getPrimaryHorizontal(start);val b=if(current.getLineForOffset(end)!=line) current.getLineRight(line) else current.getPrimaryHorizontal(end)
        val p=if(end==start) 0f else ((raw-start)/(end-start)).coerceIn(0f,1f)
        val right=(a+(b-a)*p+compoundPaddingLeft).coerceIn(0f,width.toFloat())
        val top=current.getLineTop(line)+compoundPaddingTop
        val bottom=current.getLineBottom(line)+compoundPaddingTop
        val clip=Path().apply {addRect(0f,0f,width.toFloat(),top.toFloat(),Path.Direction.CW);addRect(0f,top.toFloat(),right,bottom.toFloat(),Path.Direction.CW)}
        val save=canvas.save();canvas.clipPath(clip);super.onDraw(canvas);canvas.restoreToCount(save)
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val current=layout
        if(current!=null && fraction<1f) {
            val line=current.getLineForOffset(visibleChars.coerceAtMost(text.length))
            setMeasuredDimension(measuredWidth,current.getLineBottom(line)+compoundPaddingTop+compoundPaddingBottom)
        }
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        super.onLayout(changed,left,top,right,bottom)
        val l=layout ?: return
        val line=l.getLineForOffset(visibleChars.coerceAtMost(text.length))
        cursor((l.getLineTop(line)+l.getLineBottom(line))/2f+compoundPaddingTop)
        if(previousHeight!=height) {previousHeight=height;growth()}
    }
    override fun performClick(): Boolean=super.performClick()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN && fraction<1) {animator?.cancel();setReveal(1f)}
        return super.onTouchEvent(event)
    }
    fun dispose() {animator?.cancel();animator=null;AsyncDrawableScheduler.unschedule(this)}
    override fun onDetachedFromWindow() {dispose();super.onDetachedFromWindow()}
}

/** Markdown colours taken from the app theme (ARGB ints for Markwon). */
internal data class MarkdownPalette(val ink: Int,val accent: Int,val code: Int,val codeInk: Int,val quote: Int,val rule: Int)

@Composable fun MarkdownContent(source: String,modifier: Modifier=Modifier,animate: Boolean=false,
    onCursor: (Float)->Unit={},onGrowth: ()->Unit={},onLongPress: (()->Unit)?=null) {
    // A reply is shown whole. Text runs render through Markwon, GFM tables through MarkdownTable
    // so each column is as wide as its content, fenced code through CodeBlock. onCursor reports
    // the y of the reply's last line.
    val segments=remember(source) {splitMarkdownTables(source)}
    val cursor by rememberUpdatedState(onCursor);val growth by rememberUpdatedState(onGrowth)
    val lastLine=with(LocalDensity.current) {14.dp.toPx()}
    androidx.compose.foundation.layout.Column(modifier.onGloballyPositioned {c ->cursor(c.positionInRoot().y+(c.size.height-lastLine).coerceAtLeast(lastLine))}
        .onSizeChanged {growth()},verticalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        for(segment in segments) when(segment) {
            is MarkdownSegment.Text -> MarkdownText(segment.markdown,Modifier.fillMaxWidth(),onLongPress)
            is MarkdownSegment.Table -> MarkdownTable(segment,onLongPress)
            is MarkdownSegment.Code -> CodeBlock(segment,onLongPress)
        }
    }
}
@Composable private fun MarkdownText(source: String,modifier: Modifier,onLongPress: (()->Unit)?) {
    val context=LocalContext.current;val density=LocalDensity.current
    val colors=MaterialTheme.colorScheme;val ink=colors.onSurface.toArgb()
    val palette=MarkdownPalette(ink,colors.primary.toArgb(),colors.surfaceContainerHigh.toArgb(),colors.tertiary.toArgb(),
        colors.primary.copy(alpha=.55f).toArgb(),colors.outlineVariant.toArgb())
    val size=with(density) {MaterialTheme.typography.bodyLarge.fontSize.toPx()}*LocalChatTextScale.current
    val renderer=remember(context,palette,size) {MarkdownEngine.create(context,palette,size)}
    // produceState keeps its previous value when only its keys change. A parse from
    // the previous source must never be installed under the new source's cache key.
    val parsed by key(source,renderer) {
        produceState<Spanned?>(null) {value=withContext(Dispatchers.Default) {renderer.toMarkdown(normalizeMath(source))}}
    }
    val longPress by rememberUpdatedState(onLongPress)
    AndroidView(factory={MarkdownView(it)},modifier=modifier,onRelease={it.dispose()},update={view ->
        view.setTextColor(ink);view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size)
        // With a message menu, a long press opens it; text can still be selected from the menu.
        if(onLongPress!=null) view.setOnLongClickListener {longPress?.invoke();true} else view.setOnLongClickListener(null)
        parsed?.let {view.display(renderer,it,source,false)}
    })
}

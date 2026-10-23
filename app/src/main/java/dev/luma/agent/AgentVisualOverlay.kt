package dev.luma.agent

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import dev.luma.agent.core.AppSettings
import dev.luma.agent.core.RunState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.*

/** Physical screen coordinates; no screenshot scaling or window-inset offsets leak into the cursor. */
internal class CursorPath(var x: Float=0f,var y: Float=0f) {
    private var sx=x;private var sy=y;private var tx=x;private var ty=y
    var duration=0L;private set
    fun target(x: Float,y: Float,animated: Boolean,durationMillis: Long?=null) {
        require(x.isFinite() && y.isFinite())
        sx=this.x;sy=this.y;tx=x;ty=y
        duration=if(animated) durationMillis ?: (380+hypot(tx-sx,ty-sy)*.55f).toLong().coerceIn(380,1050) else 0
        if(!animated) {this.x=tx;this.y=ty}
    }
    fun advance(elapsed: Long): Boolean {
        val t=if(duration==0L) 1f else (elapsed.toFloat()/duration).coerceIn(0f,1f)
        val p=t*t*t*(10f+t*(-15f+6f*t))
        x=sx+(tx-sx)*p;y=sy+(ty-sy)*p
        return t>=1
    }
}

/** Where the agent is typing: caret position and height, and the screen positions of the last visible letters. */
internal class TypingCaret(val x: Float,val y: Float,val height: Float,val letters: FloatArray)

/** Two separate windows: a touch-through canvas and a small, always available stop control. */
// Absolute gravity is intentional: Accessibility coordinates are physical, including in RTL locales.
@android.annotation.SuppressLint("RtlHardcoded")
internal class AgentVisualOverlay(private val service: AgentAccessibilityService,private val graph: AppGraph,private val controller: dev.luma.agent.core.AgentController=graph.agent) {
    companion object {
        const val DRAW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
    private val manager=service.getSystemService(WindowManager::class.java)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    internal var canvas: AgentCanvas?=null;private set
    /** The Stop pill: always visible during a task, independent of the panel setting. */
    internal var stopButton: View?=null;private set
    private var stopDetail: TextView?=null
    private var hidden=false
    private var current=RunState()
    private var settings=AppSettings()
    private val stopParams=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
        gravity=Gravity.TOP or Gravity.END;x=dp(12);y=dp(52)
    }
    fun bind() {
        scope.launch {combine(controller.state,graph.vault.state) {run,s->run to s}.collect {(run,s)->
            current=run;settings=s
            if(!run.active) remove() else {
                if(canvas==null && stopButton==null) show()
                canvas?.configure(run,s)
                updateStop()
            }
        }}
        // Run time and token speed tick on the Stop button while the task is active.
        scope.launch {while(true) {delay(1000);if(current.active) updateStop()}}
    }
    /** Small line under «Стоп»: "1:05 · ↓ 38 т/с" (or "пауза"). */
    internal fun stopDetailText(): String {
        val time=if(current.startedAt>0) dev.luma.agent.core.formatElapsed(SystemClock.elapsedRealtime()-current.startedAt) else ""
        val meter=graph.api.stats.value?.takeIf {settings.tokenMeter}?.let {"↓ ${dev.luma.agent.core.formatRate(it.outputPerSecond)} т/с"}.orEmpty()
        return listOf(if(current.paused) "пауза" else "",time,meter).filter {it.isNotEmpty()}.joinToString(" · ")
    }
    private fun updateStop() {
        val detail=stopDetailText()
        stopDetail?.apply {text=detail;visibility=if(detail.isEmpty()) View.GONE else View.VISIBLE}
    }
    private fun show() {
        val layer=AgentCanvas(service)
        layer.setOrigin(graph.takeOrbHandoff())
        val params=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,DRAW_FLAGS,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            if(Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        layer.visibility=if(hidden) View.INVISIBLE else View.VISIBLE
        runCatching {manager.addView(layer,params);canvas=layer}
        graph.overlayVisible.value=canvas!=null
        // A compact pill in the app's colours: gradient stop mark, «Стоп», and time · speed in small type.
        val stop=LinearLayout(service).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            minimumHeight=dp(42);setPadding(dp(6),dp(5),dp(14),dp(5))
            background=OverlayStyle.surface(service,radius=21f);elevation=dp(6).toFloat()
            isClickable=true;isFocusable=true;contentDescription="Остановить управление Luma"
            setOnClickListener {controller.stop()}
            addView(StopMark(service),LinearLayout.LayoutParams(dp(30),dp(30)).apply {marginEnd=dp(8)})
            addView(LinearLayout(service).apply {
                orientation=LinearLayout.VERTICAL
                addView(TextView(service).apply {text="Стоп";textSize=13.5f;setTextColor(OverlayStyle.TEXT);typeface=Typeface.DEFAULT_BOLD;includeFontPadding=false})
                val detail=TextView(service).apply {textSize=10.5f;setTextColor(OverlayStyle.MUTED);includeFontPadding=false;setPadding(0,dp(2),0,0);maxLines=1}
                stopDetail=detail;addView(detail)
            })
            visibility=if(hidden) View.INVISIBLE else View.VISIBLE
        }
        updateStop()
        runCatching {manager.addView(stop,stopParams);stopButton=stop}.onFailure {
            // A visible stop affordance is required before any screen mutation.
            controller.stop("Не удалось показать кнопку остановки. Перезапустите службу управления.")
        }
    }
    suspend fun travelTo(x: Float,y: Float) {
        controller.awaitAction()
        val layer=canvas ?: return
        layer.advanceVisualFrame(SystemClock.uptimeMillis())
        while(layer.introRunning) {
            controller.awaitAction();delay(16);layer.advanceVisualFrame(SystemClock.uptimeMillis());layer.invalidate()
        }
        if(avoidStop(x,y)) delay(32)
        if(!settings.visuals.cursor) return
        val reduced=settings.reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()
        layer.cursor.target(x,y,!reduced)
        var elapsed=0L
        while(true) {
            controller.awaitAction()
            if(settings.reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()) elapsed=layer.cursor.duration
            val done=layer.cursor.advance(elapsed);layer.moving=!done;layer.invalidate()
            if(done) break
            val start=SystemClock.uptimeMillis();delay(16)
            elapsed+=(SystemClock.uptimeMillis()-start).coerceIn(1,48)
        }
        controller.awaitAction()
    }
    fun pulse() {canvas?.pulse()}
    /** Typing turns the cursor into a caret only when the cursor is shown and motion is allowed. */
    fun typingEnabled()=canvas!=null && settings.visuals.cursor && !settings.reducedMotion && android.animation.ValueAnimator.areAnimatorsEnabled()
    /** Morphs the cursor into a text caret at [caret] and waits until the morph has mostly happened. */
    suspend fun beginTyping(caret: TypingCaret) {
        canvas?.typeAt(caret) ?: return
        var waited=0
        while(waited<380) {controller.awaitAction();delay(16);waited+=16}
    }
    fun updateTyping(caret: TypingCaret,typed: String="") {canvas?.typeAt(caret,typed)}
    fun endTyping() {canvas?.stopTyping()}
    /** Follow the actual injected swipe rather than jumping directly to its end. */
    fun traceTo(x: Float,y: Float) {canvas?.traceTo(x,y)}
    suspend fun prepareGesture(x1: Float,y1: Float,x2: Float,y2: Float) {
        if(avoidStop(x1,y1,x2,y2)) delay(32) // Allow WindowManager to place the control before injection.
        controller.awaitAction()
    }
    private fun avoidStop(x: Float,y: Float,endX: Float=x,endY: Float=y): Boolean {
        val stop=stopButton ?: return false
        val metrics=android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        val w=stop.width.coerceAtLeast(dp(104));val h=stop.height.coerceAtLeast(dp(42))
        val margin=dp(12);val radius=hypot(w.toFloat(),h.toFloat())/2+dp(8)
        fun distance(cx: Float,cy: Float): Float {
            val dx=endX-x;val dy=endY-y;val length=dx*dx+dy*dy
            val t=if(length<.01f) 0f else (((cx-x)*dx+(cy-y)*dy)/length).coerceIn(0f,1f)
            return hypot(cx-x-t*dx,cy-y-t*dy)
        }
        val location=IntArray(2);stop.getLocationOnScreen(location)
        if(distance(location[0]+w/2f,location[1]+h/2f)>radius) return false
        data class Spot(val gravity: Int,val y: Int,val cx: Float,val cy: Float)
        val candidates=listOf(dp(52),(metrics.heightPixels-h-dp(48)).coerceAtLeast(dp(52))).flatMap {top ->
            listOf(Spot(Gravity.LEFT,top,margin+w/2f,top+h/2f),Spot(Gravity.RIGHT,top,metrics.widthPixels-margin-w/2f,top+h/2f))
        }
        val best=candidates.maxBy {distance(it.cx,it.cy)}
        stopParams.gravity=Gravity.TOP or best.gravity;stopParams.y=best.y;stopParams.x=margin
        runCatching {manager.updateViewLayout(stop,stopParams)}
        return true
    }
    fun temporarilyHide(value: Boolean) {
        hidden=value
        canvas?.visibility=if(value) View.INVISIBLE else View.VISIBLE
        stopButton?.visibility=if(value) View.INVISIBLE else View.VISIBLE
    }
    private fun remove() {
        graph.overlayVisible.value=false
        canvas?.let {it.dispose();runCatching {manager.removeView(it)}};canvas=null
        stopButton?.let {runCatching {manager.removeView(it)}};stopButton=null
    }
    fun destroy() {scope.cancel();remove()}
    private fun dp(n: Int)=(n*service.resources.displayMetrics.density+.5f).toInt()
}

internal class AgentCanvas(context: Context): View(context) {
    val cursor=CursorPath(resources.displayMetrics.widthPixels*.5f,resources.displayMetrics.heightPixels*.5f)
    var moving=false
    var introRunning=false;private set
    private var run=RunState();private var settings=AppSettings()
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPen=Paint()
    private val location=IntArray(2)
    private val rings=ArrayDeque<Triple<Float,Float,Long>>()
    private val trail=ArrayDeque<Pair<Float,Float>>()
    private var trace: android.animation.ValueAnimator?=null
    private var time=0f
    private var lastFrame=0L
    private var entered=false
    private var startedAt=0L
    private var origin: OrbHandoff?=null
    private var particles: CursorParticleField?=null
    internal val particleCount get()=particles?.particles?.size ?: 0
    internal var entrance=0f;private set
    private var radius=14f*resources.displayMetrics.density
    private val d=resources.displayMetrics.density
    /** Typing morph: [typing] goes 0→1 while the sphere turns into a caret and back when typing ends. */
    internal var typing=0f;private set
    private var typingTarget=0f
    private var caretX=0f;private var caretY=0f;private var caretH=0f
    /** Distance still to go, smoothed: drives the liquid stretch. */
    private var caretMotion=0f
    /** The first flight from wherever the sphere was to the field: eased over the morph, not snapped. */
    private var flying=false;private var flightX=0f;private var flightY=0f
    private var caretDirection=1f
    /** 0 at rest, up to 1 while the caret glides: thickens and squashes it, like a liquid caret. */
    internal var caretFlow=0f;private set
    /** Space hop (the caret dips into a drop and back) and new-line dive, each 0→1→0 while it plays. */
    private var hopStart=0L;internal var hop=0f;private set
    private var diveStart=0L;internal var dive=0f;private set
    private var targetX=0f;private var targetY=0f;private var targetH=0f
    private val box=RectF()
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;isClickable=false;isFocusable=false;setWillNotDraw(false)}
    fun setOrigin(value: OrbHandoff?) {if(!entered) origin=value}
    fun configure(run: RunState,settings: AppSettings) {this.run=run;this.settings=settings;invalidate()}
    fun pulse() {
        if(!settings.visuals.cursor || reduced()) return
        rings.addLast(Triple(cursor.x,cursor.y,SystemClock.uptimeMillis()))
        while(rings.size>4) rings.removeFirst()
        invalidate()
    }
    fun traceTo(x: Float,y: Float) {
        trace?.cancel()
        val sx=cursor.x;val sy=cursor.y
        if(reduced()) {cursor.x=x;cursor.y=y;invalidate();return}
        trace=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration=420;interpolator=android.view.animation.LinearInterpolator()
            addListener(object: android.animation.AnimatorListenerAdapter() {override fun onAnimationEnd(animation: android.animation.Animator) {moving=false;invalidate()}})
            addUpdateListener {if(run.active) {val t=it.animatedValue as Float;cursor.x=sx+(x-sx)*t;cursor.y=sy+(y-sy)*t;moving=t<1;invalidate()}}
            start()
        }
    }
    private fun reduced()=settings.reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()
    /** Sets where the caret should be; the drawn caret glides there, so per-letter jumps never look abrupt. */
    fun typeAt(caret: TypingCaret,typed: String="") {
        if(!caret.x.isFinite() || !caret.y.isFinite()) return
        if(typingTarget==0f && typing==0f) {caretX=cursor.x;caretY=cursor.y;caretH=radius*2;caretMotion=0f;caretFlow=0f;hopStart=0L;diveStart=0L;flying=true;flightX=cursor.x;flightY=cursor.y}
        if(!reduced() && typing>=1f) {
            val now=lastFrame // the clock of advanceVisualFrame
            // A space just typed: the caret dips into a drop at the bottom of the line and comes back, moving on with the text.
            if(' ' in typed) hopStart=now
            if('\n' in typed) diveStart=now
        }
        typingTarget=1f;targetX=caret.x;targetY=caret.y;targetH=caret.height.coerceIn(10*d,64*d)
        particles?.letters=caret.letters;particles?.lineHeight=targetH
        invalidate()
    }
    fun stopTyping() {typingTarget=0f;particles?.letters=FloatArray(0);invalidate()}
    internal val typingActive get()=typingTarget>0f || typing>0f
    internal fun advanceVisualFrame(now: Long) {
        if(!run.active) return
        if(!entered) {
            entered=true;startedAt=now;lastFrame=now
            val w=width.takeIf {it>0} ?: resources.displayMetrics.widthPixels
            val h=height.takeIf {it>0} ?: resources.displayMetrics.heightPixels
            val source=origin?.takeIf {it.x.isFinite() && it.y.isFinite() && it.radius.isFinite() && it.radius>0}
            if(source!=null) {
                cursor.x=source.x.coerceIn(0f,w.toFloat());cursor.y=source.y.coerceIn(0f,h.toFloat())
                radius=source.radius.coerceIn(6*d,80*d)
                cursor.target(w/2f,h/2f,!reduced(),850);introRunning=!reduced()
                if(reduced()) radius=14*d
            }
            val seeds=source?.seeds?.takeIf {it.isNotEmpty()} ?: dev.luma.agent.ui.OrbPhysics(56).particles.map {CursorSeed(it.x,it.y,it.z,it.size,it.shade)}
            particles=CursorParticleField(seeds,cursor.x,cursor.y,radius)
        }
        val elapsed=(now-startedAt).coerceAtLeast(0)
        entrance=if(reduced()) 1f else smooth((elapsed/700f).coerceIn(0f,1f))
        if(introRunning) {
            val done=cursor.advance(if(reduced()) cursor.duration else elapsed)
            val progress=smooth((elapsed/cursor.duration.coerceAtLeast(1).toFloat()).coerceIn(0f,1f))
            radius=(origin?.radius ?: 14*d)+(14*d-(origin?.radius ?: 14*d))*progress
            introRunning=!done;moving=!done
        }
        val dt=(now-lastFrame).coerceIn(0,50)/1000f;lastFrame=now
        if(typingActive) {
            // About 0.4 s for the sphere↔caret morph. It flies to the field during the morph, then follows the text like a
            // liquid caret: it eases toward each new letter (a quarter of the way per 60 Hz frame) and leans toward it.
            typing=if(reduced()) typingTarget else if(typingTarget>typing) (typing+dt/.42f).coerceAtMost(1f) else if(typingTarget<typing) (typing-dt/.42f).coerceAtLeast(0f) else typing
            if(typingTarget>0f) {
                if(reduced()) {caretX=targetX;caretY=targetY;caretH=targetH;flying=false}
                else if(flying) {
                    val e=smooth(typing);caretX=flightX+(targetX-flightX)*e;caretY=flightY+(targetY-flightY)*e
                    caretH+=(targetH-caretH)*(1f-exp(-dt/.08f))
                    if(typing>=1f) flying=false
                } else {
                    val k=1f-exp(-dt/.0556f)
                    caretX+=(targetX-caretX)*k;caretY+=(targetY-caretY)*k
                    caretH+=(targetH-caretH)*(1f-exp(-dt/.08f))
                }
            }
            val dx=targetX-caretX
            if(abs(dx)>.1f) caretDirection=if(dx>0) 1f else -1f
            caretMotion+=(hypot(dx,targetY-caretY)-caretMotion)*(1f-exp(-dt/.0415f))
            caretFlow=if(reduced()) 0f else (caretMotion/(15f*d)).coerceIn(0f,1f)
            fun pulse(start: Long,ms: Float): Float {val t=(now-start)/ms;return if(start==0L || t>=1f) 0f else sin(PI.toFloat()*t.coerceAtLeast(0f))}
            hop=pulse(hopStart,260f);if(hop==0f) hopStart=0L
            dive=pulse(diveStart,340f);if(dive==0f) diveStart=0L
            cursor.x=caretX;cursor.y=caretY
        }
        particles?.typing=smooth(typing);particles?.tight=if(typingTarget>0f) 1f else smooth(typing)
        if(!reduced() && !run.paused && !run.retryPending && run.approval==null) {
            time+=dt;particles?.advance(dt,cursor.x,cursor.y,radius)
        }
    }
    private fun smooth(t: Float)=t*t*t*(10f+t*(-15f+6f*t))
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(!run.active) return
        val now=SystemClock.uptimeMillis();advanceVisualFrame(now)
        val animate=!reduced() && !run.paused && !run.retryPending && run.approval==null
        if(settings.visuals.edgeGlow) drawEdge(canvas,time)
        if(settings.visuals.cursor) {
            getLocationOnScreen(location)
            val x=cursor.x-location[0];val y=cursor.y-location[1]
            val opacity=if(origin!=null) 1f else entrance
            if(settings.visuals.motionBlur && !reduced() && moving && !run.paused) {
                trail.addLast(x to y);while(trail.size>7) trail.removeFirst()
                trail.forEachIndexed {i,p->sphere(canvas,p.first,p.second,radius,(i+1)/7f*.12f*settings.visuals.blurStrength*opacity)}
            } else trail.clear()
            satellites(canvas,false,x,y,radius,opacity)
            if(typing>0f) caret(canvas,x,y,smooth(typing),opacity) else sphere(canvas,x,y,radius,opacity)
            satellites(canvas,true,x,y,radius,opacity)
            val iterator=rings.iterator()
            while(iterator.hasNext()) {
                val ring=iterator.next();val p=((now-ring.third)/650f).coerceIn(0f,1f)
                if(p>=1f) {iterator.remove();continue}
                pen.shader=null;pen.style=Paint.Style.STROKE;pen.strokeWidth=d*(1f-p*.45f)
                pen.color=Color.argb(((1-p)*175).toInt(),178,190,255)
                canvas.drawCircle(ring.first-location[0],ring.second-location[1],(16+38*(1-(1-p)*(1-p)))*d,pen)
            }
        }
        if(visibility==VISIBLE && (introRunning || entrance<1f || typingActive || (animate && (settings.visuals.edgeGlow || settings.visuals.cursor)) || rings.isNotEmpty())) postInvalidateOnAnimation()
    }
    private fun satellites(canvas: Canvas,front: Boolean,x: Float,y: Float,r: Float,opacity: Float) {
        pen.shader=null;pen.style=Paint.Style.FILL
        for(p in particles?.particles.orEmpty()) {
            if((p.z>=0)!=front) continue
            val px=p.x-location[0];val py=p.y-location[1]
            if(!front && (px-x)*(px-x)+(py-y)*(py-y)<r*r) continue
            val shade=p.seed.shade
            pen.color=Color.rgb((190+shade*12).toInt(),(185+shade*48).toInt(),255)
            pen.alpha=((.35f+shade*.5f)*opacity*255).toInt().coerceIn(0,255)
            canvas.drawCircle(px,py,(p.seed.size*.85f).coerceIn(.35f,1f)*d,pen)
        }
        pen.alpha=255
    }
    private fun drawEdge(canvas: Canvas,t: Float) {
        val paused=if(run.paused || run.approval!=null || run.retryPending) .5f else 1f
        // Composite into one capped layer: overlaps and corner bloom cannot exceed 90% opacity.
        layerPen.alpha=(.9f*settings.visuals.glowIntensity.coerceIn(0f,1f)*paused*entrance*255).toInt().coerceIn(0,229)
        val save=canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),layerPen)
        val colors=intArrayOf(0xffb7ddff.toInt(),0xff649bff.toInt(),0xffa891ff.toInt(),0xffd7c3ff.toInt(),0xfff0f8ff.toInt(),0xff8acfff.toInt(),0xffb7ddff.toInt())
        val palette=colors.mapIndexed {i,color ->
            val alpha=(.15f+.75f*((sin(t*.7f+i*1.4f)+1f)/2f)).coerceIn(0f,1f)
            Color.argb((alpha*255).toInt(),Color.red(color),Color.green(color),Color.blue(color))
        }.toIntArray().also {it[it.lastIndex]=it[0]}
        val shader=SweepGradient(width/2f,height/2f,palette,null)
        shader.setLocalMatrix(Matrix().apply {setRotate(t*13f,width/2f,height/2f)})
        pen.style=Paint.Style.FILL;pen.alpha=255
        val extent=76f*d
        val maskColors=intArrayOf(0xdfffffff.toInt(),0xb8ffffff.toInt(),0x74ffffff,0x25ffffff,0x00ffffff)
        val stops=floatArrayOf(0f,.12f,.32f,.62f,1f)
        fun field(rect: RectF,x1: Float,y1: Float,x2: Float,y2: Float) {
            val mask=LinearGradient(x1,y1,x2,y2,maskColors,stops,Shader.TileMode.CLAMP)
            pen.shader=ComposeShader(shader,mask,PorterDuff.Mode.DST_IN);canvas.drawRect(rect,pen)
        }
        field(RectF(0f,0f,width.toFloat(),extent),0f,0f,0f,extent)
        field(RectF(0f,height-extent,width.toFloat(),height.toFloat()),0f,height.toFloat(),0f,height-extent)
        field(RectF(0f,0f,extent,height.toFloat()),0f,0f,extent,0f)
        field(RectF(width-extent,0f,width.toFloat(),height.toFloat()),width.toFloat(),0f,width-extent,0f)
        pen.shader=shader;pen.style=Paint.Style.STROKE
        val rect=RectF(.7f*d,.7f*d,width-.7f*d,height-.7f*d)
        pen.strokeWidth=1.333f*d;pen.alpha=(170+50*sin(t*.8f)).toInt()
        canvas.drawRoundRect(rect,32*d,32*d,pen)
        pen.alpha=255;pen.shader=null;canvas.restoreToCount(save)
    }
    private fun sphere(canvas: Canvas,x: Float,y: Float,r: Float,opacity: Float) {
        pen.style=Paint.Style.FILL;pen.alpha=(opacity*255).toInt().coerceIn(0,255)
        pen.shader=RadialGradient(x,y,r*1.8f,intArrayOf(0x5593baff,0x0093baff),null,Shader.TileMode.CLAMP)
        canvas.drawCircle(x,y,r*1.8f,pen)
        pen.shader=RadialGradient(x-r*.37f,y-r*.42f,r*1.43f,intArrayOf(0xfff0fbff.toInt(),0xff92d7ff.toInt(),0xff5264a4.toInt()),floatArrayOf(0f,.65f,1f),Shader.TileMode.CLAMP)
        canvas.drawCircle(x,y,r,pen);pen.shader=null;pen.color=Color.WHITE;pen.alpha=(opacity*230).toInt().coerceIn(0,255)
        val star=Path();val vertices=floatArrayOf(0f,-.24f,.058f,-.058f,.24f,0f,.058f,.058f,0f,.24f,-.058f,.058f,-.24f,0f,-.058f,-.058f)
        for(i in vertices.indices step 2) {if(i==0) star.moveTo(x+vertices[i]*r,y+vertices[i+1]*r) else star.lineTo(x+vertices[i]*r,y+vertices[i+1]*r)}
        star.close();canvas.drawPath(star,pen);pen.alpha=255
    }
    /**
     * The sphere at [s]=0, a thin caret at [s]=1: one rounded shape whose size and fill change together,
     * filled with the app's white–blue–lilac gradient that flows along the caret.
     */
    private fun caret(canvas: Canvas,x: Float,y: Float,s: Float,opacity: Float) {
        // The liquid caret of the exteraGram text-animation plugin, tuned for Luma's fast typing (about 45 ms a letter,
        // so the caret is nearly always moving): while it travels it leans toward the next letter (up to 10 dp) and gets
        // up to 30 % thicker, barely shorter; on a space it hops into a drop, on a new line it dives.
        val base=2.6f*d;val line=caretH.coerceAtLeast(12*d)/.84f;val m=caretFlow
        val thick=maxOf(.55f*base,base*(1f+.3f*m+.28f*hop+.1f*dive))
        val stretch=minOf(10f*d,caretMotion*.28f+1.4f*base*hop)
        val inset=line*(.08f+.05f*m+.28f*hop+.08f*dive)
        val cx=x;val cy=y+caretH*.42f*hop+line*.28f*dive
        val shift=(cy-y)*.12f
        var left=cx-thick/2-(if(caretDirection<0) stretch else 0f);var right=cx+thick/2+(if(caretDirection>0) stretch else 0f)
        var top=y-line/2+inset+shift;var bottom=y+line/2-inset+shift;var round=thick
        if(hop>0f) {
            val drop=maxOf(2.7f*base,.36f*line)
            left+=(cx-drop/2-left)*hop;right+=(cx+drop/2-right)*hop;top+=(cy-drop-top)*hop;bottom+=(cy-bottom)*hop;round+=(drop/2-round)*hop
        }
        // From the sphere to that shape.
        box.set(x-radius+(left-x+radius)*s,y-radius+(top-y+radius)*s,x+radius+(right-x-radius)*s,y+radius+(bottom-y-radius)*s)
        val w=box.width();val h=box.height();val corner=radius+(minOf(round,w/2,h/2)-radius)*s
        val glow=maxOf(w,h)*.85f+radius*(1-s)
        pen.style=Paint.Style.FILL;pen.alpha=(opacity*255).toInt().coerceIn(0,255)
        pen.shader=RadialGradient(x,y,glow,intArrayOf(0x5593baff,0x0093baff),null,Shader.TileMode.CLAMP)
        canvas.drawCircle(x,y,glow,pen)
        if(s<1f) {
            pen.shader=RadialGradient(x-w*.18f,y-h*.21f,maxOf(w,h)*.72f,intArrayOf(0xfff0fbff.toInt(),0xff92d7ff.toInt(),0xff5264a4.toInt()),floatArrayOf(0f,.65f,1f),Shader.TileMode.CLAMP)
            pen.alpha=((1-s)*opacity*255).toInt().coerceIn(0,255);canvas.drawRoundRect(box,corner,corner,pen)
        }
        val mix=(sin(time*.8f)+1)/2
        val colors=intArrayOf(blend(0xfff9fdff.toInt(),0xffccb7ff.toInt(),mix),0xffb8e2ff.toInt(),blend(0xffccb7ff.toInt(),0xfff9fdff.toInt(),mix),0xfff9fdff.toInt())
        pen.shader=LinearGradient(x,box.top,x,box.top+h*1.6f,colors,null,Shader.TileMode.MIRROR).apply {
            setLocalMatrix(Matrix().apply {setTranslate(0f,(time*.9f%2f)*h*1.6f)})
        }
        pen.alpha=(s*opacity*255).toInt().coerceIn(0,255);canvas.drawRoundRect(box,corner,corner,pen)
        if(s<.4f) {
            pen.shader=null;pen.color=Color.WHITE;pen.alpha=((1-s/.4f)*opacity*230).toInt().coerceIn(0,255)
            canvas.drawCircle(x,y,radius*.12f*(1-s/.4f),pen)
        }
        pen.shader=null;pen.alpha=255
    }
    private fun blend(a: Int,b: Int,t: Float)=Color.argb(255,(Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())
    fun dispose() {trace?.cancel();trace=null;run=RunState();rings.clear();trail.clear();particles=null;introRunning=false;typing=0f;typingTarget=0f}
    override fun onDetachedFromWindow() {dispose();super.onDetachedFromWindow()}
}

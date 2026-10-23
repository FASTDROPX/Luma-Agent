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

/**
 * Where the agent is typing: caret position and height, and the screen positions (x,y pairs) of the letters just
 * before [end] — NaN for letters scrolled out of view. Comparing two steps shows how far a one-line field scrolled.
 */
internal class TypingCaret(val x: Float,val y: Float,val height: Float,val letters: FloatArray=FloatArray(0),val end: Int=0)

/**
 * The arrival curve from the sketch: a calm start, a quick middle and a long soft landing (a normalised logistic).
 * The path has the same S shape: the cursor leaves its place sideways, turns, and glides into the target.
 */
internal object Glide {
    private fun logistic(t: Float)=1f/(1f+exp(-12f*(t-.6f)))
    private val from=logistic(0f);private val to=logistic(1f)
    /** 0→1 along the S curve. */
    fun s(t: Float)=(logistic(t.coerceIn(0f,1f))-from)/(to-from)
    private fun ease(t: Float)=t.coerceIn(0f,1f).let {it*it*it*(10f+it*(-15f+6f*it))}
    /** The point at time [t] (0..1) on the S path from (x0,y0) to (x1,y1). */
    fun at(x0: Float,y0: Float,x1: Float,y1: Float,t: Float): Pair<Float,Float> {val u=ease(t);return x0+(x1-x0)*u to y0+(y1-y0)*s(u)}
}

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
    /** The previous task's canvas while it plays its exit. */
    internal var leavingCanvas: AgentCanvas?=null;private set
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
            if(!run.active) leave() else {
                leavingCanvas?.let {finishLeaving(it)}
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
    fun updateTyping(caret: TypingCaret) {canvas?.typeAt(caret)}
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
        leavingCanvas?.visibility=if(value) View.INVISIBLE else View.VISIBLE
        stopButton?.visibility=if(value) View.INVISIBLE else View.VISIBLE
    }
    /** The task ended: Stop goes at once, the canvas plays its exit and then goes too. */
    private fun leave() {
        stopButton?.let {runCatching {manager.removeView(it)}};stopButton=null;stopDetail=null
        val layer=canvas ?: return
        canvas=null;leavingCanvas=layer
        layer.leave {finishLeaving(layer)}
        // Frames stop when the window is hidden for a screenshot; never leave the window behind.
        scope.launch {delay(AgentCanvas.LEAVE+500);finishLeaving(layer)}
    }
    private fun finishLeaving(layer: AgentCanvas) {
        if(leavingCanvas!==layer) return
        leavingCanvas=null;layer.dispose();runCatching {manager.removeView(layer)}
        graph.overlayVisible.value=canvas!=null
    }
    private fun remove() {
        graph.overlayVisible.value=false
        leavingCanvas?.let {it.dispose();runCatching {manager.removeView(it)}};leavingCanvas=null
        canvas?.let {it.dispose();runCatching {manager.removeView(it)}};canvas=null
        stopButton?.let {runCatching {manager.removeView(it)}};stopButton=null
    }
    fun destroy() {scope.cancel();remove()}
    private fun dp(n: Int)=(n*service.resources.displayMetrics.density+.5f).toInt()
}

internal class AgentCanvas(context: Context): View(context) {
    companion object {
        /** Entry: the cursor rides the S curve to the centre while the glow spreads along the edges. */
        const val ARRIVE=1100L
        /** Exit: the glow retracts and the cursor glides off the screen. */
        const val LEAVE=900L
        /** Pause look in and out. */
        const val PAUSE=450L
    }
    private val d=resources.displayMetrics.density
    val cursor=CursorPath(resources.displayMetrics.widthPixels*.5f,resources.displayMetrics.heightPixels*.5f)
    var moving=false
    var introRunning=false;private set
    private var run=RunState();private var settings=AppSettings()
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPen=Paint()
    private val fadePen=Paint()
    private val maskPen=Paint().apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
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
    internal val particleField get()=particles
    private var radius=14f*d
    /** 0 before the task, 1 while it runs: how far the glow has spread along the edges, and how wide it has grown. */
    internal var presence=0f;private set
    /** The point on the screen edge the glow spreads from on entry and retracts to on exit. */
    internal var anchorX=0f;private set
    internal var anchorY=0f;private set
    private var startX=0f;private var startY=0f;private var startRadius=14f*d
    /** 0 running, 1 paused (or waiting for approval): a thin glow and a grey, half-transparent cursor with its particles. */
    internal var pause=0f;private set
    /** Glow width now, in pixels: 76 dp running, 5 dp paused. */
    internal val glowWidth get()=5f*d+71f*d*smooth(presence)*(1f-smooth(pause))
    internal var leaving=false;private set
    private var leftAt=0L;private var exitFromX=0f;private var exitFromY=0f;private var exitX=0f;private var exitY=0f
    private var onLeft: (()->Unit)?=null
    /** Typing morph: [typing] goes 0→1 while the sphere turns into a caret and back when typing ends. */
    internal var typing=0f;private set
    private var typingTarget=0f
    private var caretX=0f;private var caretY=0f;private var caretH=0f
    /** Distance still to go, smoothed: drives the liquid stretch. */
    private var caretMotion=0f
    /** The first flight from wherever the sphere was to the field: eased over the morph, not snapped. */
    private var flying=false;private var flightX=0f;private var flightY=0f
    private var caretDirection=1f
    /** 0 at rest, up to 1 while the caret glides: it gets a little thicker. */
    internal var caretFlow=0f;private set
    private var targetX=0f;private var targetY=0f;private var targetH=0f
    private var letters=FloatArray(0);private var lettersEnd=0
    /** The caret as last drawn, in view coordinates. */
    internal val caretBox=RectF()
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
            addUpdateListener {if(run.active && !leaving) {val t=it.animatedValue as Float;cursor.x=sx+(x-sx)*t;cursor.y=sy+(y-sy)*t;moving=t<1;invalidate()}}
            start()
        }
    }
    private fun reduced()=settings.reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()
    /** Sets where the caret should be; the drawn caret glides there, so per-letter jumps never look abrupt. */
    fun typeAt(caret: TypingCaret) {
        if(!caret.x.isFinite() || !caret.y.isFinite() || leaving) return
        if(typingTarget==0f && typing==0f) {
            caretX=cursor.x;caretY=cursor.y;caretH=radius*2;caretMotion=0f;caretFlow=0f
            flying=true;flightX=cursor.x;flightY=cursor.y;letters=FloatArray(0)
        }
        // How far the letters already on screen moved since the last step (a one-line field scrolling).
        textShift(letters,lettersEnd,caret.letters,caret.end)?.let {(dx,dy)->particles?.carry(dx,dy)}
        letters=caret.letters;lettersEnd=caret.end
        typingTarget=1f;targetX=caret.x;targetY=caret.y;targetH=caret.height.coerceIn(10*d,64*d)
        invalidate()
    }
    private fun textShift(old: FloatArray,oldEnd: Int,new: FloatArray,newEnd: Int): Pair<Float,Float>? {
        val oldFirst=oldEnd-old.size/2;val newFirst=newEnd-new.size/2
        var dx=0f;var dy=0f;var count=0
        for(i in maxOf(oldFirst,newFirst) until minOf(oldEnd,newEnd)) {
            val a=(i-oldFirst)*2;val b=(i-newFirst)*2
            if(old[a].isFinite() && old[a+1].isFinite() && new[b].isFinite() && new[b+1].isFinite()) {dx+=new[b]-old[a];dy+=new[b+1]-old[a+1];count++}
        }
        if(count==0) return null
        dx/=count;dy/=count
        return if(abs(dx)<.5f && abs(dy)<.5f || hypot(dx,dy)>400*d) null else dx to dy
    }
    fun stopTyping() {typingTarget=0f;letters=FloatArray(0);invalidate()}
    internal val typingActive get()=typingTarget>0f || typing>0f
    private fun screenWidth()=(width.takeIf {it>0} ?: resources.displayMetrics.widthPixels).toFloat()
    private fun screenHeight()=(height.takeIf {it>0} ?: resources.displayMetrics.heightPixels).toFloat()
    /** The nearest point on the screen edge. */
    private fun edgeOf(x: Float,y: Float): Pair<Float,Float> {
        val w=screenWidth();val h=screenHeight()
        val cx=x.coerceIn(0f,w);val cy=y.coerceIn(0f,h)
        val toLeft=cx;val toRight=w-cx;val toTop=cy;val toBottom=h-cy
        return when(minOf(minOf(toLeft,toRight),minOf(toTop,toBottom))) {toLeft->0f to cy;toRight->w to cy;toTop->cx to 0f;else->cx to h}
    }
    /**
     * Plays the exit — the glow retracts toward the nearest edge and the cursor glides off the screen there — then
     * calls [done]. Without visuals, with reduced motion or before the first frame it calls [done] at once.
     */
    fun leave(done: ()->Unit) {
        if(leaving) {onLeft=done;return}
        if(!entered || reduced() || visibility!=VISIBLE || (!settings.visuals.cursor && !settings.visuals.edgeGlow)) {done();return}
        trace?.cancel();introRunning=false;typingTarget=0f;flying=false
        val (ex,ey)=edgeOf(cursor.x,cursor.y)
        anchorX=ex;anchorY=ey
        exitFromX=cursor.x;exitFromY=cursor.y
        // Past the edge by a little more than the sphere and its particles.
        val w=screenWidth();val h=screenHeight();val out=radius*3+24*d
        exitX=when(ex) {0f->-out;w->w+out;else->ex};exitY=if(ex==0f || ex==w) ey else if(ey==0f) -out else h+out
        leaving=true;leftAt=SystemClock.uptimeMillis();onLeft=done;invalidate()
    }
    internal fun advanceVisualFrame(now: Long) {
        if(!run.active && !leaving) return
        val w=screenWidth();val h=screenHeight()
        if(!entered) {
            entered=true;startedAt=now;lastFrame=now
            val source=origin?.takeIf {it.x.isFinite() && it.y.isFinite() && it.radius.isFinite() && it.radius>0}
            // The cursor leaves its place — the chat sphere when the task came from the chat, otherwise from just below the screen.
            startX=source?.x?.coerceIn(0f,w) ?: (w/2f);startY=source?.y?.coerceIn(0f,h) ?: (h+28*d)
            startRadius=source?.radius?.coerceIn(6*d,80*d) ?: (14*d)
            edgeOf(startX,startY).let {(x,y)->anchorX=x;anchorY=y}
            if(reduced()) {
                if(source!=null) {cursor.x=w/2f;cursor.y=h/2f}
                radius=14*d;presence=1f
            } else {cursor.x=startX;cursor.y=startY;radius=startRadius;introRunning=true;moving=true}
            val seeds=source?.seeds?.takeIf {it.isNotEmpty()} ?: dev.luma.agent.ui.OrbPhysics(56).particles.map {CursorSeed(it.x,it.y,it.z,it.size,it.shade)}
            particles=CursorParticleField(seeds,cursor.x,cursor.y,radius)
        }
        val dt=(now-lastFrame).coerceIn(0,50)/1000f;lastFrame=now
        if(introRunning) {
            val t=((now-startedAt)/ARRIVE.toFloat()).coerceIn(0f,1f)
            val (x,y)=Glide.at(startX,startY,w/2f,h/2f,t);cursor.x=x;cursor.y=y
            radius=startRadius+(14*d-startRadius)*Glide.s(t)
            presence=smooth(t)
            if(t>=1f) {introRunning=false;moving=false}
        }
        if(leaving) {
            val t=((now-leftAt)/LEAVE.toFloat()).coerceIn(0f,1f)
            val g=Glide.s(t)
            cursor.x=exitFromX+(exitX-exitFromX)*g;cursor.y=exitFromY+(exitY-exitFromY)*g
            presence=1f-smooth(t);moving=t<1f
            if(t>=1f) {leaving=false;val done=onLeft;onLeft=null;if(done!=null) android.os.Handler(android.os.Looper.getMainLooper()).post(done)}
        }
        val held=run.active && (run.paused || run.approval!=null || run.retryPending)
        pause=if(reduced()) (if(held) 1f else 0f) else if(held) (pause+dt*1000f/PAUSE).coerceAtMost(1f) else (pause-dt*1000f/PAUSE).coerceAtLeast(0f)
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
            if(!leaving) {cursor.x=caretX;cursor.y=caretY}
        }
        particles?.typing=smooth(typing);particles?.tight=if(flying) 1f else 0f
        // A pause slows everything down to a stop together with the grey fade.
        val speed=1f-smooth(pause)
        if(!reduced() && speed>0f) {time+=dt*speed;particles?.advance(dt*speed,cursor.x,cursor.y,radius)}
    }
    private fun smooth(t: Float)=t*t*t*(10f+t*(-15f+6f*t))
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(!run.active && !leaving) return
        val now=SystemClock.uptimeMillis();advanceVisualFrame(now)
        if(!run.active && !leaving) return
        val gray=smooth(pause)
        if(settings.visuals.edgeGlow && presence>0f) drawEdge(canvas,time)
        if(settings.visuals.cursor) {
            getLocationOnScreen(location)
            val x=cursor.x-location[0];val y=cursor.y-location[1]
            // Paused: the cursor and its particles turn grey and half-transparent together.
            val faded=if(gray>0f) {
                fadePen.colorFilter=ColorMatrixColorFilter(ColorMatrix().apply {setSaturation(1f-gray)})
                fadePen.alpha=((1f-.5f*gray)*255).toInt().coerceIn(0,255)
                canvas.saveLayer(null,fadePen)
            } else -1
            if(settings.visuals.motionBlur && !reduced() && moving && gray<1f) {
                trail.addLast(x to y);while(trail.size>7) trail.removeFirst()
                trail.forEachIndexed {i,p->sphere(canvas,p.first,p.second,radius,(i+1)/7f*.12f*settings.visuals.blurStrength)}
            } else trail.clear()
            satellites(canvas,false,x,y,radius,1f)
            if(typing>0f) caret(canvas,x,y,smooth(typing),1f) else sphere(canvas,x,y,radius,1f)
            satellites(canvas,true,x,y,radius,1f)
            val iterator=rings.iterator()
            while(iterator.hasNext()) {
                val ring=iterator.next();val p=((now-ring.third)/650f).coerceIn(0f,1f)
                if(p>=1f) {iterator.remove();continue}
                pen.shader=null;pen.style=Paint.Style.STROKE;pen.strokeWidth=d*(1f-p*.45f)
                pen.color=Color.argb(((1-p)*175).toInt(),178,190,255)
                canvas.drawCircle(ring.first-location[0],ring.second-location[1],(16+38*(1-(1-p)*(1-p)))*d,pen)
            }
            if(faded>=0) canvas.restoreToCount(faded)
        }
        val settling=introRunning || leaving || (pause>0f && pause<1f) || typingActive || rings.isNotEmpty()
        val animate=!reduced() && pause<1f && (settings.visuals.edgeGlow || settings.visuals.cursor)
        if(visibility==VISIBLE && (settling || animate)) postInvalidateOnAnimation()
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
        // Composite into one capped layer: overlaps and corner bloom cannot exceed 90% opacity.
        layerPen.alpha=(.9f*settings.visuals.glowIntensity.coerceIn(0f,1f)*(presence*3f).coerceAtMost(1f)*255).toInt().coerceIn(0,229)
        val save=canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),layerPen)
        val colors=intArrayOf(0xffb7ddff.toInt(),0xff649bff.toInt(),0xffa891ff.toInt(),0xffd7c3ff.toInt(),0xfff0f8ff.toInt(),0xff8acfff.toInt(),0xffb7ddff.toInt())
        val palette=colors.mapIndexed {i,color ->
            val alpha=(.15f+.75f*((sin(t*.7f+i*1.4f)+1f)/2f)).coerceIn(0f,1f)
            Color.argb((alpha*255).toInt(),Color.red(color),Color.green(color),Color.blue(color))
        }.toIntArray().also {it[it.lastIndex]=it[0]}
        val shader=SweepGradient(width/2f,height/2f,palette,null)
        shader.setLocalMatrix(Matrix().apply {setRotate(t*13f,width/2f,height/2f)})
        pen.style=Paint.Style.FILL;pen.alpha=255
        val extent=glowWidth
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
        if(presence<1f) {
            // Entry and exit: only the part of the frame around the anchor is lit; it spreads both ways along the edges
            // until the ends meet on the opposite side (and retracts the same way).
            getLocationOnScreen(location)
            val cx=width/2f;val cy=height/2f
            val angle=atan2(anchorY-location[1]-cy,anchorX-location[0]-cx)
            val feather=.45f;val reach=(PI.toFloat()+feather)*smooth(presence)-feather
            val steps=72
            val lit=IntArray(steps+1) {i ->
                val a=(if(i<=steps/2) i else steps-i)*2f*PI.toFloat()/steps
                Color.argb((((reach+feather-a)/feather).coerceIn(0f,1f)*255).toInt(),255,255,255)
            }
            maskPen.shader=SweepGradient(cx,cy,lit,null).apply {setLocalMatrix(Matrix().apply {setRotate(Math.toDegrees(angle.toDouble()).toFloat(),cx,cy)})}
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),maskPen)
            maskPen.shader=null
        }
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
        // The liquid caret of the exteraGram text-animation plugin, tuned for Luma's fast typing: while it travels it
        // stretches toward the next letter (up to 10 dp) and gets up to 30 % thicker. Its height never changes.
        val base=2.6f*d;val tall=caretH.coerceAtLeast(12*d)
        val thick=base*(1f+.3f*caretFlow)
        val stretch=minOf(10f*d,caretMotion*.28f)
        val left=x-thick/2-(if(caretDirection<0) stretch else 0f);val right=x+thick/2+(if(caretDirection>0) stretch else 0f)
        val top=y-tall/2;val bottom=y+tall/2
        // From the sphere to that shape.
        val box=caretBox
        box.set(x-radius+(left-x+radius)*s,y-radius+(top-y+radius)*s,x+radius+(right-x-radius)*s,y+radius+(bottom-y-radius)*s)
        val w=box.width();val h=box.height();val corner=radius+(minOf(thick,w/2,h/2)-radius)*s
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
    fun dispose() {trace?.cancel();trace=null;run=RunState();rings.clear();trail.clear();particles=null;introRunning=false;typing=0f;typingTarget=0f;leaving=false;onLeft=null}
    override fun onDetachedFromWindow() {dispose();super.onDetachedFromWindow()}
}

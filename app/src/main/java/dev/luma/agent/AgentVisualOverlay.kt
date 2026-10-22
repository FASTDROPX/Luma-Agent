package dev.luma.agent

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.*
import android.widget.Button
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
    internal var stopButton: Button?=null;private set
    private var hidden=false
    private var current=RunState()
    private var settings=AppSettings()
    private val stopParams=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
        gravity=Gravity.TOP or Gravity.END;x=dp(12);y=dp(56)
    }
    fun bind() {scope.launch {combine(controller.state,graph.vault.state) {run,s->run to s}.collect {(run,s)->
        current=run;settings=s
        if(!run.active) remove() else {
            if(canvas==null && stopButton==null) show()
            canvas?.configure(run,s)
            stopButton?.text=if(run.paused) "■ Стоп · пауза" else "■ Стоп"
        }
    }}}
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
        val stop=Button(service).apply {
            text="■ Стоп";isAllCaps=false;textSize=14f;contentDescription="Остановить управление Luma"
            minHeight=dp(48);minimumHeight=dp(48);setPadding(dp(18),dp(8),dp(18),dp(8))
            setTextColor(0xffe9e8ff.toInt());backgroundTintList=null
            background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xff273653.toInt(),0xff393053.toInt())).apply {
                cornerRadius=dp(24).toFloat();setStroke(dp(1),0x999fbfff.toInt())
            }
            setOnClickListener {controller.stop()}
            visibility=if(hidden) View.INVISIBLE else View.VISIBLE
        }
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
        val w=stop.width.coerceAtLeast(dp(112));val h=stop.height.coerceAtLeast(dp(48))
        val margin=dp(12);val radius=hypot(w.toFloat(),h.toFloat())/2+dp(8)
        fun distance(cx: Float,cy: Float): Float {
            val dx=endX-x;val dy=endY-y;val length=dx*dx+dy*dy
            val t=if(length<.01f) 0f else (((cx-x)*dx+(cy-y)*dy)/length).coerceIn(0f,1f)
            return hypot(cx-x-t*dx,cy-y-t*dy)
        }
        val location=IntArray(2);stop.getLocationOnScreen(location)
        if(distance(location[0]+w/2f,location[1]+h/2f)>radius) return false
        data class Spot(val gravity: Int,val y: Int,val cx: Float,val cy: Float)
        val candidates=listOf(dp(56),(metrics.heightPixels-h-dp(48)).coerceAtLeast(dp(56))).flatMap {top ->
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
            sphere(canvas,x,y,radius,opacity)
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
        if(visibility==VISIBLE && (introRunning || entrance<1f || (animate && (settings.visuals.edgeGlow || settings.visuals.cursor)) || rings.isNotEmpty())) postInvalidateOnAnimation()
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
    fun dispose() {trace?.cancel();trace=null;run=RunState();rings.clear();trail.clear();particles=null;introRunning=false}
    override fun onDetachedFromWindow() {dispose();super.onDetachedFromWindow()}
}

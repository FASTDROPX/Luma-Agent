package dev.luma.agent

import kotlin.math.*

internal data class CursorSeed(val x: Float,val y: Float,val z: Float,val size: Float,val shade: Float)
internal data class OrbAnchor(val x: Float,val y: Float,val radius: Float,val at: Long,val seeds: ()->List<CursorSeed>)
internal data class OrbHandoff(val x: Float,val y: Float,val radius: Float,val seeds: List<CursorSeed>)

/**
 * Satellites of the agent cursor, moving like the welcome-screen arrival: while the cursor flies they
 * stretch into a narrow, slightly bent stream along its path (each particle follows the path with its
 * own delay), and on arrival they wind back onto their tilted orbits with a swirl that slowly decays.
 */
internal class CursorParticleField(seeds: List<CursorSeed>,x: Float,y: Float,radius: Float) {
    class Particle(val seed: CursorSeed,val ux: Float,val uy: Float,val uz: Float,val vx: Float,val vy: Float,val vz: Float,val lag: Float,val bend: Float) {
        var x=0f;var y=0f;var z=0f
        var dx=0f;var dy=0f;var dz=0f
    }
    val particles: List<Particle>
    private var time=0.0
    /** 0 while the cursor rests, 1 while it flies fast. */
    var motion=0f;private set
    /** Swirl energy kept from the flight; spins the orbits after arrival and decays. */
    private var excite=0f
    private var swirl=0.0
    private var lastX=x;private var lastY=y
    private var headingX=1f;private var headingY=0f
    private val trail=ArrayDeque<FloatArray>()
    /**
     * Typing: 0 = orbits around the cursor, 1 = particles sit on the letters just typed. [letters] holds screen
     * positions (x0,y0,x1,y1,…, oldest first) of the last visible letters; when a one-line field scrolls, those
     * positions slide left and the particles follow them, so they never pile up at a caret that stays put.
     */
    var typing=0f
    /** 1 from the moment typing starts: a short stream, so particles keep up with the caret's flight into the field. */
    var tight=0f
    var letters=FloatArray(0)
    var lineHeight=0f
    init {
        val startX=x;val startY=y
        val chosen=seeds.take(64)
        particles=chosen.mapIndexed {i,s ->
            val length=sqrt(s.x*s.x+s.y*s.y+s.z*s.z).coerceAtLeast(.01f)
            val ux=s.x/length;val uy=s.y/length;val uz=s.z/length
            val a=if(abs(uy)<.9f) Triple(0f,1f,0f) else Triple(0f,0f,1f)
            val bx=a.second*uz-a.third*uy;val by=a.third*ux-a.first*uz;val bz=a.first*uy-a.second*ux
            val n=sqrt(bx*bx+by*by+bz*bz).coerceAtLeast(.01f)
            // Position in the stream: spread evenly, lightly shuffled by shade so the line is not a ladder.
            val order=((i+.5f)/chosen.size.coerceAtLeast(1)*.8f+s.shade*.2f).coerceIn(0f,1f)
            Particle(s,ux,uy,uz,bx/n,by/n,bz/n,lag=.025f+.30f*order,bend=(s.shade-.5f)*2f)
                .apply {this.x=startX+s.x*radius;this.y=startY+s.y*radius;z=s.z*radius}
        }
        trail.addLast(floatArrayOf(0f,x,y))
    }
    /** Cursor position [seconds] ago, interpolated along the recorded path. */
    private fun pathAt(t: Double,fallbackX: Float,fallbackY: Float): Pair<Float,Float> {
        if(trail.isEmpty()) return fallbackX to fallbackY
        if(t<=trail.first()[0]) return trail.first()[1] to trail.first()[2]
        for(i in trail.size-1 downTo 1) {
            val b=trail[i];val a=trail[i-1]
            if(t>=a[0]) {
                val span=(b[0]-a[0]).coerceAtLeast(1e-6f);val k=((t-a[0])/span).toFloat().coerceIn(0f,1f)
                return a[1]+(b[1]-a[1])*k to a[2]+(b[2]-a[2])*k
            }
        }
        return trail.last()[1] to trail.last()[2]
    }
    fun advance(seconds: Float,x: Float,y: Float,radius: Float) {
        if(!seconds.isFinite() || seconds<=0 || !x.isFinite() || !y.isFinite() || !radius.isFinite()) return
        val dt=seconds.coerceAtMost(.1f);val steps=ceil(dt*240f).toInt().coerceAtLeast(1);val h=dt/steps
        val moveX=x-lastX;val moveY=y-lastY;val distance=hypot(moveX,moveY)
        // About 14 radii per second is a full stream; a slow drift barely stretches it.
        val target=(distance/dt/(radius.coerceAtLeast(1f)*14f)).coerceIn(0f,1f)
        if(distance>.5f) {headingX=moveX/distance;headingY=moveY/distance}
        val velocityX=moveX/dt;val velocityY=moveY/dt
        for(step in 1..steps) {
            time+=h
            val f=step.toFloat()/steps
            val ax=lastX+moveX*f;val ay=lastY+moveY*f
            trail.addLast(floatArrayOf(time.toFloat(),ax,ay))
            // The stream forms quickly on take-off and relaxes more slowly on arrival.
            motion+=(target-motion)*(1f-exp(-h*(if(target>motion) 10f else 3.2f)))
            excite=maxOf(excite*exp(-h/.9f),motion)
            swirl+=h*7.0*excite*(1f-motion)
            val spread=1f-.82f*motion
            for(p in particles) {
                // While typing the stream is shorter: particles keep up with the caret's flight into the field.
                val (px,py)=pathAt(time-p.lag*motion*(1f-.75f*tight),ax,ay)
                // A gentle sideways bow makes the stream curve like the welcome-screen arrival.
                val bow=sin(PI.toFloat()*p.lag/.33f)*radius*.9f*motion*p.bend
                val cx=px-headingY*bow;val cy=py+headingX*bow
                val a=time*(.55+p.seed.shade*.65)+swirl*(.7+.6*p.seed.shade)
                val c=cos(a).toFloat();val s=sin(a).toFloat()
                val r=radius*(1.35f+p.seed.shade*.30f)*spread
                var tx=cx+r*(p.ux*c+p.vx*s);var ty=cy+r*(p.uy*c+p.vy*s);var tz=r*(p.uz*c+p.vz*s)
                if(typing>0f) {
                    val (lx,ly,lz)=letterTarget(p,x,y,radius)
                    tx+=(lx-tx)*typing;ty+=(ly-ty)*typing;tz+=(lz-tz)*typing
                }
                val omega=11f+p.seed.shade*4f
                // While typing, damping works against the cursor's own velocity: the cloud flies with the caret
                // instead of trailing far behind it at speed.
                p.dx=(p.dx+((tx-p.x)*omega*omega-1.8f*omega*(p.dx-velocityX*tight))*h).coerceIn(-15000f,15000f)
                p.dy=(p.dy+((ty-p.y)*omega*omega-1.8f*omega*(p.dy-velocityY*tight))*h).coerceIn(-15000f,15000f)
                p.dz=(p.dz+((tz-p.z)*omega*omega-1.8f*omega*p.dz)*h).coerceIn(-15000f,15000f)
                p.x+=p.dx*h;p.y+=p.dy*h;p.z+=p.dz*h
            }
        }
        lastX=x;lastY=y
        while(trail.size>2 && trail.first()[0]<time-.6) trail.removeFirst()
    }
    /**
     * Where a particle rests while typing: on one of the recent letters (the caret [x],[y] counts as the newest),
     * hovering a little above or below the line. Particles that stay close to the caret take the newest letters.
     */
    internal fun letterTarget(p: Particle,x: Float,y: Float,radius: Float): Triple<Float,Float,Float> {
        val count=letters.size/2
        val order=((p.lag-.025f)/.30f).coerceIn(0f,1f)
        // Positions along the text, newest last; the caret closes the chain.
        val slots=count+1
        val k=(1f-order)*(slots-1)
        val i=floor(k).toInt().coerceIn(0,slots-1);val j=(i+1).coerceAtMost(slots-1);val f=k-i
        fun px(n: Int)=if(n>=count) x else letters[n*2]
        fun py(n: Int)=if(n>=count) y else letters[n*2+1]
        val bx=px(i)+(px(j)-px(i))*f;val by=py(i)+(py(j)-py(i))*f
        val h=(if(lineHeight>0f) lineHeight else radius*1.4f)
        val phase=time*(1.1+p.seed.shade*1.3)+p.bend*3.0
        val ox=(cos(phase*.7).toFloat()*.18f)*h
        val oy=(sin(phase).toFloat()*(.35f+.3f*p.seed.shade))*h
        return Triple(bx+ox,by+oy,cos(phase).toFloat()*radius)
    }
}

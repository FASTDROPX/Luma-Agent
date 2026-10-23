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
     * Typing (0..1): the cloud gets smaller and flatter around the caret. Particles always chase the cursor and, while
     * the text grows, trail behind it without catching up. In a full one-line field the caret stays put and the text
     * scrolls; [carry] moves the particles with the letters, so they keep chasing instead of piling up on the caret.
     */
    var typing=0f
    /** 1 during the caret's flight into the field: a short stream that flies along with the caret. */
    var tight=0f
    /** Moves the particles and the recent cursor path by the distance the text just scrolled. */
    fun carry(dx: Float,dy: Float) {
        if(!dx.isFinite() || !dy.isFinite()) return
        for(p in particles) {p.x+=dx;p.y+=dy}
        for(point in trail) {point[1]+=dx;point[2]+=dy}
        lastX+=dx;lastY+=dy
    }
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
                // During the caret's flight the stream is shorter, so the particles fly along with it; while typing it is
                // half as long: a short tail right behind the caret.
                val (px,py)=pathAt(time-p.lag*motion*(1f-.75f*tight)*(1f-.5f*typing),ax,ay)
                // A gentle sideways bow makes the stream curve like the welcome-screen arrival.
                val bow=sin(PI.toFloat()*p.lag/.33f)*radius*.9f*motion*p.bend
                val cx=px-headingY*bow;val cy=py+headingX*bow
                val a=time*(.55+p.seed.shade*.65)+swirl*(.7+.6*p.seed.shade)
                val c=cos(a).toFloat();val s=sin(a).toFloat()
                val r=radius*(1.35f+p.seed.shade*.30f)*spread*(1f-.4f*typing)
                val tx=cx+r*(p.ux*c+p.vx*s);val ty=cy+r*(p.uy*c+p.vy*s)*(1f-.35f*typing);val tz=r*(p.uz*c+p.vz*s)
                // A little firmer while typing: the tail stays close behind the caret even when a field scrolls fast.
                val omega=(11f+p.seed.shade*4f)*(1f+.4f*typing)
                // During that flight damping works against the cursor's own velocity: the cloud flies with the caret
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
}

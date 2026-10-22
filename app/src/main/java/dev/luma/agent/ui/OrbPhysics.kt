package dev.luma.agent.ui

import kotlin.math.*
import kotlin.random.Random

/** A weighted trackball, independent satellite springs and a curved capture stream. */
class OrbPhysics(count: Int = 144, orbitSpeed: Float = 1f, arrivalDistance: Float = 0f, returnDelay: Float = 3f) {
    data class Point(val x: Float,val y: Float,val z: Float)
    internal data class Capture(val start: Point,val control1: Point,val control2: Point,val end: Point,
        val launch: Double,val duration: Double,val angle: Double,val swirl: Double,val decay: Double) {
        val at get()=launch+duration
    }
    class Particle(val radius: Float,val size: Float,val shade: Float,val phase: Double,val speed: Double,
        val ux: Float,val uy: Float,val uz: Float,val vx: Float,val vy: Float,val vz: Float,val eccentricity: Float) {
        var x=0f;internal set
        var y=0f;internal set
        var z=0f;internal set
        val behindSphere get()=z<0f && x*x+y*y<1f
        internal var capture: Capture?=null
        internal var yaw=0f;internal var pitch=0f
        internal var yawVelocity=0f;internal var pitchVelocity=0f
        internal var stretch=0f;internal var stretchVelocity=0f
    }
    val particles: List<Particle>
    var time=0.0;private set
    private var phaseTime=0.0
    private val distance=if(arrivalDistance.isFinite()) arrivalDistance.coerceIn(0f,80f) else 0f
    private val restDelay=if(returnDelay.isFinite()) returnDelay.coerceIn(2f,6f) else 3f
    val hasArrival get()=distance>0f
    private var arrivalSkipped=false
    val allCaptured get()=arrivalSkipped || !hasArrival || particles.all {time>=(it.capture?.at ?: 0.0)}
    val arrivalX: Float get() {
        if(arrivalSkipped || !hasArrival) return 0f
        val t=(time/.85).coerceIn(0.0,1.0)
        return (distance*(1.0-t*t*t*(10.0+t*(-15.0+6.0*t)))).toFloat()
    }
    val arrivalY get()=-arrivalX*.12f
    var yaw=0f;private set
    var pitch=0f;private set
    private var yawVelocity=0f
    private var pitchVelocity=0f
    private var targetYaw=0f
    private var targetPitch=0f
    var isPressed=false;private set
    var isDragging=false;private set
    private var holdAge=0.0
    private var idleAge=0.0
    var charge=0f;private set
    val orbitScale get()=1f+.32f*charge
    val speedMultiplier get()=1f+4f*charge
    init {
        val random=Random(0x4c554d41)
        val speed=if(orbitSpeed.isFinite()) orbitSpeed.coerceIn(.4f,2f) else 1f
        particles=List(count.coerceIn(36,180)) {i ->
            val radius=1.30f+random.nextFloat()*.39f
            val tilt=.87f+random.nextFloat()*.28f
            val twist=-.38f+(random.nextFloat()-.5f)*.24f
            val uX=cos(twist);val uY=sin(twist)
            Particle(radius,.38f+random.nextFloat()*.7f,random.nextFloat(),i*2.399963229728653,
                (.42/radius.toDouble().pow(1.5))*speed,uX,uY,0f,-uY*cos(tilt),uX*cos(tilt),sin(tilt),.03f+random.nextFloat()*.045f)
                .also {p -> if(hasArrival) p.capture=capturePath(p)}
        }
        update()
    }
    private fun capturePath(p: Particle): Capture {
        val angle=PI/2+.09*sin(p.phase)
        val end=orbitalPoint(p,angle,1f)
        val launch=.14+p.shade*1.0
        val duration=.85+.35*p.shade
        val swirl=4.5+2.0*p.shade
        val decay=.5+.9*(.5+.5*cos(p.phase))
        val tangent=orbitalPoint(p,angle+PI/2,1f)
        val handle=((p.speed+swirl)*duration/3).toFloat()
        // A thin stream approaches from the upper right, bends under the sphere,
        // and joins each tilted orbit with matching position and tangential velocity.
        val start=Point(distance+4f+sin(p.phase).toFloat()*.2f,-4f-distance*.06f+cos(p.phase).toFloat()*.15f,0f)
        return Capture(start,Point(start.x*.56f,2.5f+p.shade*.6f,.15f*sin(p.phase).toFloat()),
            Point(end.x-tangent.x*handle,end.y-tangent.y*handle,end.z-tangent.z*handle),
            end,launch,duration,angle,swirl,decay)
    }
    fun advance(seconds: Float) {
        if(!seconds.isFinite() || seconds<=0) return
        val dt=seconds.coerceAtMost(.1f).toDouble()
        val steps=ceil(dt*240.0).toInt().coerceAtLeast(1)
        repeat(steps) {step(dt/steps)}
        update()
    }
    fun beginTouch(): Boolean {
        if(!allCaptured) return false
        isPressed=true;isDragging=false;holdAge=0.0;idleAge=0.0
        targetYaw=yaw;targetPitch=pitch
        return true
    }
    /** Drag moves a spring target, not the sphere itself; roughly half the previous sensitivity. */
    fun drag(dx: Float,dy: Float,seconds: Float) {
        if(!isPressed || !dx.isFinite() || !dy.isFinite() || !seconds.isFinite()) return
        isDragging=true;holdAge=0.0
        targetYaw=yaw+(wrap(targetYaw-yaw)+(dx*.42f).coerceIn(-.6f,.6f)).coerceIn(-.9f,.9f)
        targetPitch=(targetPitch+(-dy*.36f).coerceIn(-.45f,.45f)).coerceIn(-1.05f,1.05f)
    }
    fun endTouch() {
        if(!isPressed) return
        if(isDragging) {
            // Retain energy when release occurs between two display frames. A stationary
            // finger lets the spring settle, so an old swipe cannot replay on release.
            yawVelocity=(yawVelocity+wrap(targetYaw-yaw)*4f).coerceIn(-2.6f,2.6f)
            pitchVelocity=(pitchVelocity+(targetPitch-pitch)*4f).coerceIn(-1.8f,1.8f)
        }
        isPressed=false;isDragging=false;holdAge=0.0;idleAge=0.0
    }
    fun settleMotion() {
        endTouch();arrivalSkipped=true;charge=0f;yaw=0f;pitch=0f;yawVelocity=0f;pitchVelocity=0f
        targetYaw=0f;targetPitch=0f
        particles.forEach {it.yaw=0f;it.pitch=0f;it.yawVelocity=0f;it.pitchVelocity=0f;it.stretch=0f;it.stretchVelocity=0f}
        update()
    }
    fun project(x: Float,y: Float,z: Float): Point=rotate(x,y,z,yaw,pitch)
    private fun step(dt: Double) {
        time+=dt
        val h=dt.toFloat()
        if(isPressed && !isDragging) holdAge+=dt
        val target=if(isPressed && !isDragging && holdAge>.18) 1.0 else 0.0
        val rate=if(target==1.0) .66 else 2.4
        val old=charge.toDouble();val decay=exp(-rate*dt)
        charge=(target+(old-target)*decay).toFloat().coerceIn(0f,1f)
        phaseTime+=dt+4.0*(target*dt+(old-target)*(1.0-decay)/rate)
        if(isPressed) {
            val omega=15f
            yawVelocity=(yawVelocity+(wrap(targetYaw-yaw)*omega*omega-1.8f*omega*yawVelocity)*h).coerceIn(-2.6f,2.6f)
            pitchVelocity=(pitchVelocity+((targetPitch-pitch)*omega*omega-1.8f*omega*pitchVelocity)*h).coerceIn(-1.8f,1.8f)
            yaw+=yawVelocity*h;pitch=(pitch+pitchVelocity*h).coerceIn(-1.05f,1.05f)
        } else {
            idleAge+=dt
            if(idleAge<restDelay) {
                val friction=exp(-1.6*dt).toFloat()
                yaw+=yawVelocity*(1f-friction)/1.6f
                pitch=(pitch+pitchVelocity*(1f-friction)/1.6f).coerceIn(-1.05f,1.05f)
                yawVelocity*=friction;pitchVelocity*=friction
            } else {
                yaw=wrap(yaw)
                val d=exp(-4.0*dt).toFloat()
                val jy=yawVelocity+4f*yaw;val jp=pitchVelocity+4f*pitch
                yaw=(yaw+jy*h)*d;yawVelocity=(yawVelocity-4f*jy*h)*d
                pitch=(pitch+jp*h)*d;pitchVelocity=(pitchVelocity-4f*jp*h)*d
            }
        }
        val spin=(sqrt(yawVelocity*yawVelocity+pitchVelocity*pitchVelocity)/2.6f).coerceAtMost(1f)
        particles.forEach {p ->
            val omega=5f+p.shade*2.5f
            p.yawVelocity+=(wrap(yaw-p.yaw)*omega*omega-1.6f*omega*p.yawVelocity)*h
            p.pitchVelocity+=((pitch-p.pitch)*omega*omega-1.6f*omega*p.pitchVelocity)*h
            p.yaw+=p.yawVelocity*h;p.pitch+=p.pitchVelocity*h
            p.stretchVelocity+=((spin*.12f-p.stretch)*36f-12f*p.stretchVelocity)*h
            p.stretch=(p.stretch+p.stretchVelocity*h).coerceIn(0f,.12f)
        }
    }
    private fun update() {
        val flightX=arrivalX;val flightY=arrivalY
        particles.forEach {p ->
            val capture=p.capture.takeUnless {arrivalSkipped}
            if(capture!=null && time<capture.at) {
                val u=((time-capture.launch)/capture.duration).coerceIn(0.0,1.0).toFloat()
                val v=1f-u;val a=v*v*v;val b=3*v*v*u;val c=3*v*u*u;val d=u*u*u
                p.x=a*capture.start.x+b*capture.control1.x+c*capture.control2.x+d*capture.end.x-flightX
                p.y=a*capture.start.y+b*capture.control1.y+c*capture.control2.y+d*capture.end.y-flightY
                p.z=a*capture.start.z+b*capture.control1.z+c*capture.control2.z+d*capture.end.z
            } else {
                val angle=if(capture==null) p.phase+phaseTime*p.speed else
                    capture.angle+(phaseTime-capture.at)*p.speed+capture.swirl*capture.decay*(1.0-exp(-(time-capture.at)/capture.decay))
                val orbit=orbitalPoint(p,angle,orbitScale*(1f+p.stretch))
                val q=rotate(orbit.x,orbit.y,orbit.z,p.yaw,p.pitch)
                p.x=q.x;p.y=q.y;p.z=q.z
            }
        }
    }
    private fun orbitalPoint(p: Particle,angle: Double,scale: Float): Point {
        val x=cos(angle).toFloat()*p.radius*scale;val y=sin(angle).toFloat()*p.radius*scale*(1f-p.eccentricity)
        return Point(x*p.ux+y*p.vx,x*p.uy+y*p.vy,x*p.uz+y*p.vz)
    }
    private fun rotate(x: Float,y: Float,z: Float,yaw: Float,pitch: Float): Point {
        val cx=cos(yaw);val sx=sin(yaw);val cy=cos(pitch);val sy=sin(pitch)
        val a=cx*x+sx*z;val b=-sx*x+cx*z
        return Point(a,cy*y-sy*b,sy*y+cy*b)
    }
    private fun wrap(value: Float)=atan2(sin(value),cos(value))
}

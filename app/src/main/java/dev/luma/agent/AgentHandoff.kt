package dev.luma.agent

import kotlin.math.*

internal data class CursorSeed(val x: Float,val y: Float,val z: Float,val size: Float,val shade: Float)
internal data class OrbAnchor(val x: Float,val y: Float,val radius: Float,val at: Long,val seeds: ()->List<CursorSeed>)
internal data class OrbHandoff(val x: Float,val y: Float,val radius: Float,val seeds: List<CursorSeed>)

/** A moving gravitational-looking attractor with damped velocity, independent of screen gestures. */
internal class CursorParticleField(seeds: List<CursorSeed>,x: Float,y: Float,radius: Float) {
    class Particle(val seed: CursorSeed,val ux: Float,val uy: Float,val uz: Float,val vx: Float,val vy: Float,val vz: Float) {
        var x=0f;var y=0f;var z=0f
        var dx=0f;var dy=0f;var dz=0f
    }
    val particles: List<Particle>
    private var time=0.0
    init {
        val startX=x;val startY=y
        particles=seeds.take(64).map {s ->
            val length=sqrt(s.x*s.x+s.y*s.y+s.z*s.z).coerceAtLeast(.01f)
            val ux=s.x/length;val uy=s.y/length;val uz=s.z/length
            val a=if(abs(uy)<.9f) Triple(0f,1f,0f) else Triple(0f,0f,1f)
            val bx=a.second*uz-a.third*uy;val by=a.third*ux-a.first*uz;val bz=a.first*uy-a.second*ux
            val n=sqrt(bx*bx+by*by+bz*bz).coerceAtLeast(.01f)
            Particle(s,ux,uy,uz,bx/n,by/n,bz/n).apply {this.x=startX+s.x*radius;this.y=startY+s.y*radius;z=s.z*radius}
        }
    }
    fun advance(seconds: Float,x: Float,y: Float,radius: Float) {
        if(!seconds.isFinite() || seconds<=0 || !x.isFinite() || !y.isFinite() || !radius.isFinite()) return
        val dt=seconds.coerceAtMost(.1f);val steps=ceil(dt*240f).toInt().coerceAtLeast(1);val h=dt/steps
        repeat(steps) {
            time+=h
            for(p in particles) {
                val a=time*(.55+p.seed.shade*.65);val c=cos(a).toFloat();val s=sin(a).toFloat()
                val r=radius*(1.35f+p.seed.shade*.30f)
                val tx=x+r*(p.ux*c+p.vx*s);val ty=y+r*(p.uy*c+p.vy*s);val tz=r*(p.uz*c+p.vz*s)
                val omega=11f+p.seed.shade*4f
                p.dx=(p.dx+((tx-p.x)*omega*omega-1.8f*omega*p.dx)*h).coerceIn(-15000f,15000f)
                p.dy=(p.dy+((ty-p.y)*omega*omega-1.8f*omega*p.dy)*h).coerceIn(-15000f,15000f)
                p.dz=(p.dz+((tz-p.z)*omega*omega-1.8f*omega*p.dz)*h).coerceIn(-15000f,15000f)
                p.x+=p.dx*h;p.y+=p.dy*h;p.z+=p.dz*h
            }
        }
    }
}

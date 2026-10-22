package dev.luma.agent

import dev.luma.agent.ui.OrbPhysics
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OrbPhysicsTest {
    @Test fun satellitesStayInTheirTiltedEllipticalPlanes() {
        val scene=OrbPhysics()
        repeat(3600) {scene.advance(1f/120f)}
        scene.particles.forEach {p ->
            val nx=p.uy*p.vz-p.uz*p.vy;val ny=p.uz*p.vx-p.ux*p.vz;val nz=p.ux*p.vy-p.uy*p.vx
            assertEquals(0f,p.x*nx+p.y*ny+p.z*nz,.0001f)
            val u=p.x*p.ux+p.y*p.uy+p.z*p.uz;val v=p.x*p.vx+p.y*p.vy+p.z*p.vz
            assertEquals(1f,u*u/(p.radius*p.radius)+v*v/(p.radius*p.radius*(1-p.eccentricity).pow(2)),.0001f)
        }
    }
    @Test fun everySatelliteCrossesFrontRearAndOccludedSegments() {
        val scene=OrbPhysics();val p=scene.particles.first()
        var front=false;var rear=false;var hidden=false
        repeat(7200) {scene.advance(1f/120f);front=front || p.z>0;rear=rear || p.z<0;hidden=hidden || p.behindSphere}
        assertTrue(front && rear && hidden)
    }
    @Test fun thirtySixtyAndOneTwentyFpsGiveTheSameOrbit() {
        val a=OrbPhysics();val b=OrbPhysics();val c=OrbPhysics()
        repeat(180) {a.advance(1f/30)};repeat(360) {b.advance(1f/60)};repeat(720) {c.advance(1f/120)}
        a.particles.indices.forEach {i ->
            assertEquals(a.particles[i].x,b.particles[i].x,.0001f)
            assertEquals(a.particles[i].y,c.particles[i].y,.0001f)
            assertEquals(a.particles[i].z,c.particles[i].z,.0001f)
        }
    }
    @Test fun longResumeGapsAndInvalidTimesCannotThrowParticlesOutOfOrbit() {
        val scene=OrbPhysics()
        repeat(3000) {scene.advance(if(it%2==0) Float.MAX_VALUE else .016f)}
        val time=scene.time;scene.advance(Float.NaN);scene.advance(-2f);scene.advance(Float.POSITIVE_INFINITY)
        assertEquals(time,scene.time,0.0)
        assertTrue(scene.particles.all {it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && sqrt(it.x*it.x+it.y*it.y+it.z*it.z)<1.7f})
    }
    @Test fun sizeAndSpeedSettingsPreserveTheSameOrbitalGeometry() {
        val a=OrbPhysics(144,1f);val b=OrbPhysics(144,2f)
        repeat(120) {a.advance(1f/60)};repeat(60) {b.advance(1f/60)}
        assertEquals(a.particles[0].x,b.particles[0].x,.0001f)
        assertEquals(36,OrbPhysics(1).particles.size);assertEquals(180,OrbPhysics(1000).particles.size)
    }
    @Test fun arrivalStreamTrailsTheBodyThenGetsCapturedIntoRealOrbits() {
        val scene=OrbPhysics(arrivalDistance=22f)
        assertEquals(22f,scene.arrivalX,0f)
        assertFalse(scene.beginTouch())
        repeat(36) {scene.advance(1f/60)}
        assertTrue(scene.arrivalX in 0f..6f)
        assertTrue(scene.particles.map {it.x}.average()>3.0)
        assertFalse(scene.allCaptured)
        repeat(144) {scene.advance(1f/60)}
        assertEquals(0f,scene.arrivalX,0f)
        assertTrue(scene.allCaptured)
        assertTrue(scene.particles.any {it.behindSphere})
        assertTrue(scene.particles.all {sqrt(it.x*it.x+it.y*it.y+it.z*it.z) in 1.15f..1.7f})
        assertTrue(scene.beginTouch())
    }
    @Test fun arrivalSpringsAgreeAcrossFrameRatesAndDoNotExplodeOnResume() {
        val a=OrbPhysics(arrivalDistance=22f);val b=OrbPhysics(arrivalDistance=22f);val c=OrbPhysics(arrivalDistance=22f)
        repeat(60) {a.advance(1f/30)};repeat(120) {b.advance(1f/60)};repeat(240) {c.advance(1f/120)}
        for(i in a.particles.indices) {
            assertEquals(a.particles[i].x,b.particles[i].x,.005f)
            assertEquals(a.particles[i].y,c.particles[i].y,.005f)
        }
        repeat(100) {a.advance(Float.MAX_VALUE)}
        assertTrue(a.particles.all {it.x.isFinite() && it.y.isFinite() && it.z.isFinite()})
    }
    @Test fun sevenSecondHoldApproachesSpeedLimitWithFallingAcceleration() {
        val scene=OrbPhysics();assertTrue(scene.beginTouch())
        var last=scene.charge
        repeat(120) {scene.advance(1f/60);assertTrue(scene.charge>=last);last=scene.charge}
        val early=scene.charge
        repeat(120) {scene.advance(1f/60)}
        val middle=scene.charge
        repeat(180) {scene.advance(1f/60)}
        val seven=scene.charge
        assertTrue("Expected a soft approach around 7 seconds: $seven",seven in .985f.. .995f)
        assertTrue(middle-early>seven-middle)
        assertTrue(scene.orbitScale in 1.31f..1.32f)
        assertTrue(scene.speedMultiplier in 4.94f..5f)
        repeat(1800) {scene.advance(1f/60);assertTrue(scene.speedMultiplier<=5f);assertTrue(scene.orbitScale<=1.32f)}
        assertEquals(0f,scene.yaw,0f);assertEquals(0f,scene.pitch,0f)
        assertTrue(scene.particles.all {sqrt(it.x*it.x+it.y*it.y+it.z*it.z)<2.24f})
    }
    @Test fun releaseDeceleratesAndContractsContinuouslyInsteadOfSnapping() {
        val scene=OrbPhysics();scene.beginTouch();repeat(420) {scene.advance(1f/60)}
        val speed=scene.speedMultiplier;val radius=scene.orbitScale
        scene.endTouch()
        assertEquals(speed,scene.speedMultiplier,0f);assertEquals(radius,scene.orbitScale,0f)
        scene.advance(1f/60)
        assertTrue(scene.speedMultiplier in 4.7f..speed)
        repeat(240) {scene.advance(1f/60)}
        assertEquals(1f,scene.speedMultiplier,.001f);assertEquals(1f,scene.orbitScale,.001f)
        assertFalse(scene.isPressed)
    }
    @Test fun dragRotatesInPlaceAndReturnsHomeAfterInertiaAndInactivity() {
        val scene=OrbPhysics();scene.beginTouch();scene.drag(.6f,.3f,.05f)
        assertEquals(0f,scene.yaw,0f) // a weighted spring follows the target over time
        repeat(24) {scene.advance(1f/120)}
        assertTrue(scene.yaw in .15f.. .28f);assertTrue(scene.pitch<-.05f);assertTrue(scene.isDragging)
        val direction=scene.project(0f,0f,1f)
        assertTrue(direction.x>.15f)
        repeat(420) {scene.advance(1f/60)}
        assertEquals(0f,scene.charge,0f) // dragging is not a long press
        scene.endTouch();repeat(600) {scene.advance(1f/60)}
        assertEquals(0f,scene.yaw,.0001f);assertEquals(0f,scene.pitch,.0001f)
        assertEquals(0f,scene.arrivalX,0f)
        assertTrue(scene.particles.all {sqrt(it.x*it.x+it.y*it.y+it.z*it.z)<1.7f})
    }
    @Test fun quickTapsAndReducedMotionDoNotLeaveLatentChargeOrArrival() {
        val scene=OrbPhysics();scene.beginTouch();repeat(6) {scene.advance(1f/60)};scene.endTouch()
        assertEquals(0f,scene.charge,0f)
        scene.beginTouch();repeat(420) {scene.advance(1f/60)};scene.drag(.6f,.2f,.05f)
        scene.settleMotion()
        assertEquals(0f,scene.charge,0f);assertEquals(0f,scene.yaw,0f);assertFalse(scene.isPressed)
        val flight=OrbPhysics(arrivalDistance=22f);flight.settleMotion()
        assertEquals(0f,flight.arrivalX,0f)
        assertTrue(flight.allCaptured)
        assertTrue(flight.particles.all {sqrt(it.x*it.x+it.y*it.y+it.z*it.z)<1.7f})
    }
    @Test fun releaseKeepsMomentumAndParticlesLagThenCatchUpIndependently() {
        val scene=OrbPhysics();scene.beginTouch();scene.drag(1f,.2f,.06f)
        repeat(10) {scene.advance(1f/120)}
        val before=scene.yaw
        assertTrue(before in .03f.. .3f)
        assertTrue(scene.particles.any {abs(it.yaw-scene.yaw)>.02f})
        assertTrue(scene.particles.map {it.yaw}.max()-scene.particles.map {it.yaw}.min()>.001f)
        scene.endTouch();repeat(60) {scene.advance(1f/120)}
        assertTrue("Inertia should carry the sphere after release",scene.yaw>before+.15f)
        assertTrue(scene.particles.any {it.stretch>.01f})
        repeat(1440) {scene.advance(1f/120)}
        assertEquals(0f,scene.yaw,.001f)
        assertTrue(scene.particles.all {abs(it.yaw)<.001f && it.stretch<.001f})
    }
    @Test fun stationaryFingerDoesNotReplayAnOldSwipeWhenReleased() {
        val scene=OrbPhysics();scene.beginTouch();scene.drag(1f,0f,.1f)
        repeat(360) {scene.advance(1f/120)}
        val settled=scene.yaw
        scene.endTouch();repeat(60) {scene.advance(1f/120)}
        assertEquals(settled,scene.yaw,.001f)
    }
    @Test fun captureStreamIsElongatedAndEntersEachOrbitWithoutATeleport() {
        val scene=OrbPhysics(arrivalDistance=22f)
        repeat(108) {scene.advance(1f/120)}
        val xs=scene.particles.map {it.x}
        assertTrue("Particles must arrive as a long stream, not a translated halo",xs.max()-xs.min()>10f)
        val p=scene.particles.first();val entry=p.capture!!.at
        while(scene.time<entry-.002) scene.advance(minOf(.001,entry-.002-scene.time).toFloat())
        val before=Triple(p.x,p.y,p.z)
        scene.advance(.004f)
        val gap=sqrt((p.x-before.first).pow(2)+(p.y-before.second).pow(2)+(p.z-before.third).pow(2))
        assertTrue("Stream/orbit join jumped by $gap",gap<.08f)
        repeat(600) {scene.advance(1f/120)}
        assertTrue(scene.allCaptured)
        assertTrue(scene.particles.any {it.z>0} && scene.particles.any {it.z<0})
    }
}

package dev.luma.agent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=LumaApp::class,qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayTest {
    @Before fun setup() {TestKeys.install()}
    @After fun teardown() {TestKeys.restore()}
    @Test fun cursorLayerPassesTouchesStopSurvivesHiddenPanelAndAllLayersHideForScreenshots() = runBlocking {
        val app=ApplicationProvider.getApplicationContext<LumaApp>();val graph=app.graph
        graph.vault.saveConnection(Provider.GEMINI,"fake-key","")
        graph.vault.saveModels(Provider.GEMINI,graph.vault.state.value.connection(),listOf(ModelInfo("gemini-test")))
        graph.vault.selectModel(Provider.GEMINI,"gemini-test")
        graph.vault.update {it.copy(panel=false,visuals=it.visuals.copy(cursor=false,edgeGlow=false))}
        val api=ProviderClient {JSONObject("""{"candidates":[{"content":{"parts":[{"text":"{\"type\":\"tool\",\"tool\":\"tap\",\"args\":{\"node\":1}}"}]}}]}""")}
        val jobScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=AgentController(app,jobScope,graph.vault,graph.chats,api)
        val done=CompletableDeferred<Unit>()
        val service=Robolectric.buildService(AgentAccessibilityService::class.java).create().get()
        val overlay=AgentVisualOverlay(service,graph,controller)
        try {
            val id=graph.chats.ensure("overlay test");controller.start(id,"test") {done.complete(Unit)}
            withTimeout(5000) {controller.state.first {it.approval!=null}}
            overlay.bind();shadowOf(android.os.Looper.getMainLooper()).idle()
            val layer=overlay.canvas!!;val stop=overlay.stopButton!!
            assertEquals(View.VISIBLE,stop.visibility)
            val flags=(layer.layoutParams as WindowManager.LayoutParams).flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,layer.importantForAccessibility)
            overlay.temporarilyHide(true);assertEquals(View.INVISIBLE,layer.visibility);assertEquals(View.INVISIBLE,stop.visibility)
            overlay.temporarilyHide(false);assertEquals(View.VISIBLE,layer.visibility);assertEquals(View.VISIBLE,stop.visibility)
            stop.performClick();withTimeout(5000) {done.await()};shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(controller.state.value.active);assertNull(overlay.stopButton);assertNull(overlay.canvas)
        } finally {overlay.destroy();controller.stop();jobScope.cancel();graph.scope.cancel();service.onDestroy()}
    }
    @Test fun glowIsConfinedToScreenEdgesWithATransparentCenter() {
        val app=ApplicationProvider.getApplicationContext<LumaApp>()
        val view=AgentCanvas(app);view.configure(RunState(active=true),AppSettings(reducedMotion=true,visuals=VisualSettings(cursor=false,glowIntensity=1f)))
        view.layout(0,0,411,891)
        val bitmap=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(205,445)))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(1,445))>android.graphics.Color.alpha(bitmap.getPixel(9,445)))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(32,445))>0)
        assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(100,445)))
        val pixels=IntArray(411*891);bitmap.getPixels(pixels,0,411,0,0,411,891)
        assertTrue(pixels.maxOf {android.graphics.Color.alpha(it)}<=229)
        view.dispose();app.graph.scope.cancel()
    }
    @Test fun nativeCursorAndEdgeGlowRenderWithOrbitingParticles() {
        val app=ApplicationProvider.getApplicationContext<LumaApp>()
        val view=AgentCanvas(app);view.configure(RunState(active=true),AppSettings(reducedMotion=true))
        view.layout(0,0,411,891);view.cursor.x=208f;view.cursor.y=390f;view.pulse()
        val bitmap=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(208,390))>200)
        assertEquals(56,view.particleCount)
        assertTrue((0 until 891).any {android.graphics.Color.alpha(bitmap.getPixel(2,it))>0})
        val dir=File("build/previews").apply {mkdirs()}
        val preview=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888)
        Canvas(preview).apply {drawColor(0xff10131b.toInt());drawBitmap(bitmap,0f,0f,null)}
        File(dir,"25-agent-cursor.png").outputStream().use {preview.compress(Bitmap.CompressFormat.PNG,100,it)}
        view.configure(RunState(),AppSettings());val stopped=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);view.draw(Canvas(stopped))
        assertEquals(0,stopped.getPixel(208,390));view.dispose();app.graph.scope.cancel()
    }
    private class Frames(val view: AgentCanvas) {
        val looper=shadowOf(android.os.Looper.getMainLooper())
        fun run(ms: Int) {repeat(ms/16) {looper.idleFor(java.time.Duration.ofMillis(16));view.advanceVisualFrame(android.os.SystemClock.uptimeMillis())}}
        fun draw(): Bitmap {val b=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888);view.draw(Canvas(b));return b}
    }
    @Test fun entryLeavesTheChatSphereAlongTheSCurveWhileTheGlowSpreadsFromThere() {
        val app=ApplicationProvider.getApplicationContext<LumaApp>()
        val seeds=dev.luma.agent.ui.OrbPhysics(56).particles.map {CursorSeed(it.x,it.y,it.z,it.size,it.shade)}
        val view=AgentCanvas(app);view.setOrigin(OrbHandoff(65f,680f,24f,seeds));view.configure(RunState(active=true),AppSettings())
        view.layout(0,0,411,891)
        val frames=Frames(view)
        view.advanceVisualFrame(android.os.SystemClock.uptimeMillis())
        assertEquals(65f,view.cursor.x,0f);assertEquals(680f,view.cursor.y,0f);assertEquals(0f,view.presence,0f);assertTrue(view.introRunning)
        assertEquals(0f,view.anchorX,0f);assertEquals(680f,view.anchorY,0f) // the glow starts at the edge nearest to the sphere
        // The S curve: it detaches calmly (sideways first), rushes through the middle and lands softly.
        val ys=mutableListOf<Float>();val xs=mutableListOf<Float>()
        repeat(69) {frames.run(16);xs+=view.cursor.x;ys+=view.cursor.y}
        val quarter=ys.size/4
        assertTrue(680f-ys[quarter]<12f && xs[quarter]-65f>3f)
        val rise=ys.zipWithNext {a,b->a-b}
        assertTrue(rise.indexOf(rise.max()) in ys.size*2/5..ys.size*3/4)
        assertTrue(rise.takeLast(8).all {it in -.01f..2f})
        // Halfway the glow has spread along the left edge from the sphere, not yet to the far side.
        view.dispose();val again=AgentCanvas(app);again.setOrigin(OrbHandoff(65f,680f,24f,seeds));again.configure(RunState(active=true),AppSettings())
        again.layout(0,0,411,891);val f2=Frames(again)
        again.advanceVisualFrame(android.os.SystemClock.uptimeMillis());f2.run(560)
        assertTrue(again.presence in .3f..<1f)
        val half=f2.draw()
        assertTrue(android.graphics.Color.alpha(half.getPixel(1,680))>0)
        assertEquals(0,android.graphics.Color.alpha(half.getPixel(409,150)))
        f2.run(700)
        assertEquals(205.5f,again.cursor.x,.01f);assertEquals(445.5f,again.cursor.y,.01f);assertFalse(again.introRunning)
        assertEquals(1f,again.presence,0f);assertEquals(56,again.particleCount)
        assertTrue(android.graphics.Color.alpha(f2.draw().getPixel(409,150))>0)
        again.dispose();app.graph.scope.cancel()
    }
    @Test fun pauseThinsTheGlowAndGreysTheCursorThenBringsThemBack() {
        val app=ApplicationProvider.getApplicationContext<LumaApp>()
        val view=AgentCanvas(app);view.configure(RunState(active=true),AppSettings());view.layout(0,0,411,891)
        val frames=Frames(view)
        view.advanceVisualFrame(android.os.SystemClock.uptimeMillis());frames.run(1300)
        assertEquals(76f,view.glowWidth,.01f)
        fun centre()=frames.draw().getPixel(213,453) // on the sphere, where its gradient is blue
        val colour=centre()
        assertTrue(android.graphics.Color.alpha(colour)>230 && android.graphics.Color.blue(colour)-android.graphics.Color.red(colour)>20)
        view.configure(RunState(active=true,paused=true),AppSettings())
        frames.run(208);assertTrue(view.pause in .2f..<1f);assertTrue(view.glowWidth in 6f..<75f)
        frames.run(400);assertEquals(1f,view.pause,0f);assertEquals(5f,view.glowWidth,.01f)
        val grey=centre()
        assertTrue(android.graphics.Color.alpha(grey) in 110..145)
        assertTrue(abs(android.graphics.Color.blue(grey)-android.graphics.Color.red(grey))<10)
        view.configure(RunState(active=true),AppSettings());frames.run(600)
        assertEquals(0f,view.pause,0f);assertEquals(76f,view.glowWidth,.01f)
        assertTrue(android.graphics.Color.alpha(centre())>230)
        view.dispose();app.graph.scope.cancel()
    }
    @Test fun exitRetractsTheGlowAndTheCursorGlidesOffTheNearestEdge() {
        val app=ApplicationProvider.getApplicationContext<LumaApp>()
        val view=AgentCanvas(app);view.configure(RunState(active=true),AppSettings());view.layout(0,0,411,891)
        val frames=Frames(view)
        view.advanceVisualFrame(android.os.SystemClock.uptimeMillis());frames.run(1300)
        view.cursor.x=60f;view.cursor.y=300f
        var done=false
        view.leave {done=true}
        assertTrue(view.leaving);assertEquals(0f,view.anchorX,0f);assertEquals(300f,view.anchorY,0f)
        frames.run(448);assertTrue(view.cursor.x<60f && view.cursor.x>-60f);assertTrue(view.presence in .1f..<.95f);assertFalse(done)
        frames.run(500);frames.looper.idle()
        assertTrue(done);assertFalse(view.leaving);assertEquals(0f,view.presence,0f)
        assertTrue(view.cursor.x<-30f);assertEquals(300f,view.cursor.y,.01f)
        view.dispose();app.graph.scope.cancel()
    }
}

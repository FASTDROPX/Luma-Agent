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
        val api=ProviderClient {JSONObject("""{"candidates":[{"content":{"parts":[{"text":"{\"type\":\"tool\",\"tool\":\"delete_file\",\"args\":{\"path\":\"x.txt\"}}"}]}}]}""")}
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
    @Test fun handoffStartsAtChatOrbAndEndsAtScreenCentreWhileGlowFadesIn() {
        val app=ApplicationProvider.getApplicationContext<LumaApp>()
        val seeds=dev.luma.agent.ui.OrbPhysics(56).particles.map {CursorSeed(it.x,it.y,it.z,it.size,it.shade)}
        val view=AgentCanvas(app);view.setOrigin(OrbHandoff(65f,680f,24f,seeds));view.configure(RunState(active=true),AppSettings())
        view.layout(0,0,411,891)
        val start=android.os.SystemClock.uptimeMillis();view.advanceVisualFrame(start)
        assertEquals(65f,view.cursor.x,0f);assertEquals(680f,view.cursor.y,0f);assertEquals(0f,view.entrance,0f);assertTrue(view.introRunning)
        repeat(30) {view.advanceVisualFrame(start+(it+1)*16)}
        assertTrue(view.cursor.x in 65f..205.5f);assertTrue(view.entrance in .5f..1f)
        repeat(35) {view.advanceVisualFrame(start+480+(it+1)*16)}
        assertEquals(205.5f,view.cursor.x,.001f);assertEquals(445.5f,view.cursor.y,.001f);assertFalse(view.introRunning)
        assertEquals(1f,view.entrance,0f);assertEquals(56,view.particleCount)
        view.dispose();app.graph.scope.cancel()
    }
}

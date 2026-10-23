package dev.luma.agent

import dev.luma.agent.core.*
import dev.luma.agent.ui.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SettingsEffectsTest {
    @Test fun cursorUsesZeroSpeedAtBothEndsAndParticlesFollowAMovingAttractor() {
        val path=CursorPath(0f,0f);path.target(1000f,0f,true,1000)
        path.advance(100);val first=path.x;path.advance(200);val second=path.x-first
        path.advance(500);assertEquals(500f,path.x,.001f)
        path.advance(900);val beforeEnd=path.x;path.advance(1000)
        assertTrue(first<second);assertEquals(first,1000f-beforeEnd,.001f);assertEquals(1000f,path.x,0f)
        val seeds=OrbPhysics(36).particles.map {CursorSeed(it.x,it.y,it.z,it.size,it.shade)}
        val field=CursorParticleField(seeds,10f,20f,14f)
        assertTrue(field.particles.all {it.x in -20f..40f && it.y in -10f..50f})
        repeat(60) {field.advance(1f/60,(it+1)*10f,400f,14f)}
        assertTrue(field.particles.any {it.x<570f})
        repeat(240) {field.advance(1f/60,600f,400f,14f)}
        assertTrue(field.particles.all {it.x.isFinite() && kotlin.math.abs(it.x-600f)<30 && kotlin.math.abs(it.y-400f)<30})
    }
    @Test fun systemPromptReachesChatAndAgentSystemFieldsForEveryProvider() = runTest {
        for(provider in Provider.entries) for(agent in listOf(false,true)) {
            val connection=Connection("fake-key","test-model","http://127.0.0.1:1234/v1/",parameters=mapOf("test-model" to GenerationSettings(instruction="CUSTOM_STYLE_123")))
            val system=if(agent) ModelPrompts.agent(AppSettings(confirmActions=false),connection,false) else ModelPrompts.chat(connection)
            val api=ProviderClient {r ->
                val body=r.body!!
                val sent=when(provider) {
                    Provider.GEMINI->body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
                    Provider.CLAUDE->body.getString("system")
                    else->body.getJSONArray("messages").getJSONObject(0).getString("content")
                }
                assertEquals(system,sent);assertTrue(sent.contains("CUSTOM_STYLE_123"))
                if(agent) assertTrue(sent.contains(AgentProtocol.system))
                response(provider)
            }
            assertEquals("ok",api.generate(provider,connection,listOf(Turn("user","hello")),system,agent))
        }
    }
    @Test fun fullAccessSkipsAllLumaConfirmationsAndAskModeStillWaits() {
        fun tool(name: String,args: JSONObject=JSONObject())=AgentReply.Tool(name,args,"")
        for(name in listOf("tap","tap_point","swipe","scroll","set_text","open_app","write_text","home","back")) {
            assertFalse(name,AgentApprovalPolicy.requiresApproval(tool(name),false))
            assertTrue(name,AgentApprovalPolicy.requiresApproval(tool(name),true))
        }
        for(name in listOf("screen","list_apps","read_text","list_files","wait")) assertFalse(AgentApprovalPolicy.requiresApproval(tool(name),true))
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("delete_file"),false))
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("move_file"),false))
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("write_text",JSONObject().put("overwrite",true)),false))
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("tap"),false,true))
        assertFalse(AppSettings.parse(AppSettings(confirmActions=false).json()).confirmActions)
    }
    @Test fun oldVaultRetainsConnectionAndGetsCompatibleDefaults() {
        val settings=AppSettings.parse(JSONObject("""{"version":1,"provider":"GEMINI","connections":{"GEMINI":{"key":"old-key","model":"gemini-test"}},"theme":"dark"}"""))
        assertEquals("old-key",settings.connection().key);assertEquals("gemini-test",settings.connection().model)
        assertEquals(VisualSettings(),settings.visuals);assertEquals(GenerationSettings(),settings.connection().generation())
        assertEquals(20,settings.maxSteps);assertEquals(8,settings.taskMinutes)
    }
    @Test fun modelParametersAreSeparateAndClampedOnDisk() {
        val settings=AppSettings().withGeneration(Provider.GEMINI,"first") {GenerationSettings(100_000,9f,.01f,200,"A")}
            .withGeneration(Provider.GEMINI,"second") {GenerationSettings(1024,.3f,.8f,6,"B")}
            .withGeneration(Provider.OPENAI,"first") {GenerationSettings(instruction="C")}
            .copy(visuals=VisualSettings(orbitSpeed=100f,returnDelay=-5f,particleCount=999))
        val restored=AppSettings.parse(settings.json())
        // No 8192 ceiling any more: a manual limit is kept and later capped by the model's own maximum.
        assertEquals(100_000,restored.connection(Provider.GEMINI).parameters["first"]!!.maxTokens)
        assertEquals("B",restored.connection(Provider.GEMINI).parameters["second"]!!.instruction)
        assertEquals("C",restored.connection(Provider.OPENAI).parameters["first"]!!.instruction)
        assertEquals(180,restored.visuals.particleCount);assertEquals(2f,restored.visuals.orbitSpeed);assertEquals(2f,restored.visuals.returnDelay)
        assertEquals(-1f,GenerationSettings(temperature=Float.NaN).sanitized().temperature)
    }
    @Test fun everyProviderReceivesItsOwnParameterNamesAndModelLimit() = runTest {
        for(provider in Provider.entries) {
            val api=ProviderClient {r ->
                val body=r.body!!;val config=if(provider==Provider.GEMINI) body.getJSONObject("generationConfig") else body
                val tokenField=when(provider) {Provider.GEMINI->"maxOutputTokens";Provider.OPENAI->"max_completion_tokens";else->"max_tokens"}
                assertEquals(2048,config.getInt(tokenField));assertEquals(.65,config.getDouble("temperature"),.0001)
                if(provider!=Provider.CLAUDE) assertEquals(.8,config.getDouble(if(provider==Provider.GEMINI) "topP" else "top_p"),.0001)
                else assertFalse(config.has("top_p"))
                response(provider)
            }
            val c=Connection("fake-key","model","http://127.0.0.1:1234/v1/",listOf(ModelInfo("model",outputLimit=2048)),parameters=mapOf("model" to GenerationSettings(4096,.65f,.8f)))
            assertEquals("ok",api.generate(provider,c,listOf(Turn("user","hello")),"system"))
        }
    }
    @Test fun reasoningAndAgentRequestsOmitUserSampling() = runTest {
        for((provider,model,agent) in listOf(Triple(Provider.OPENAI,"gpt-5-mini",false),Triple(Provider.DEEPSEEK,"deepseek-reasoner",false),Triple(Provider.GEMINI,"gemini-test",true))) {
            val api=ProviderClient {r ->
                val body=if(provider==Provider.GEMINI) r.body!!.getJSONObject("generationConfig") else r.body!!
                assertFalse(body.has("temperature"));assertFalse(body.has("top_p"));assertFalse(body.has("topP"));response(provider)
            }
            api.generate(provider,Connection("key",model,parameters=mapOf(model to GenerationSettings(temperature=.8f,topP=.7f))),listOf(Turn("user","q")),"system",agent)
        }
    }
    @Test fun satellitesTravelBehindTheSphereAndStayOutsideItsSurface() {
        val scene=OrbPhysics()
        assertTrue(scene.particles.any {it.behindSphere})
        val initiallyHidden=scene.particles.map {it.behindSphere}
        repeat(1200) {scene.advance(1f/120)}
        assertTrue(scene.particles.indices.any {initiallyHidden[it]!=scene.particles[it].behindSphere})
        assertTrue(scene.particles.all {sqrt(it.x*it.x+it.y*it.y+it.z*it.z)>1.15f})
    }
    @Test fun cursorMovesMonotonicallyAndRetargetsWithoutJumping() {
        val path=CursorPath(10f,20f);path.target(800f,600f,true)
        assertEquals(10f,path.x);assertFalse(path.advance(path.duration/2));val half=path.x
        assertTrue(half>10 && half<800);val y=path.y
        path.target(2f,3f,true);path.advance(0);assertEquals(half,path.x);assertEquals(y,path.y)
        assertTrue(path.advance(10_000));assertEquals(2f,path.x);assertEquals(3f,path.y)
        path.target(0f,0f,false);assertEquals(0f,path.x);assertEquals(0L,path.duration)
        assertTrue(AgentVisualOverlay.DRAW_FLAGS and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
    }
    @Test fun inputInsertionSupportsMidTextPasteAndEmojiWithoutChangingOffsets() {
        assertEquals(2..4,insertedRange("abcd","abXYZcd"));assertNull(insertedRange("abc","ab"));assertNull(insertedRange("abc","abc"))
        assertEquals(0..1,insertedRange("","🌙"))
        val text=androidx.compose.ui.text.AnnotatedString("ab🌙cd")
        val rendered=GlyphFade(2..3,.3f,androidx.compose.ui.graphics.Color.White).filter(text)
        assertEquals(text.text,rendered.text.text)
        for(i in 0..text.length) {assertEquals(i,rendered.offsetMapping.originalToTransformed(i));assertEquals(i,rendered.offsetMapping.transformedToOriginal(i))}
    }
    @Test fun screenshotCoordinatesMapToPhysicalPixelsAndRejectOffScreenPoints() {
        assertEquals(540f to 960f,physicalPoint(400,600,800,1200,1080,1920))
        assertEquals(0f to 0f,physicalPoint(0,0,800,1200,1080,1920))
        for(point in listOf(-1 to 0,800 to 0,0 to 1200)) {
            try {physicalPoint(point.first,point.second,800,1200,1080,1920);fail("Off-screen action accepted")} catch(_: UserError) {}
        }
    }
    private fun response(p: Provider)=JSONObject(when(p) {Provider.GEMINI->"""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}""";Provider.CLAUDE->"""{"content":[{"type":"text","text":"ok"}]}""";else->"""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""})
}

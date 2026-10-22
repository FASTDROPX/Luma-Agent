package dev.luma.agent

import android.app.Application
import dev.luma.agent.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class AgentContextTest {
    private fun tool(name: String = "screen", args: JSONObject = JSONObject()) = AgentReply.Tool(name,args,"")

    @Test fun thousandStepsKeepFixedWorkingSetAndLatestSnapshotOnly() {
        val memory = AgentContext(listOf(Turn("user","Открой Google")),"Открой Google")
        memory.remember("Осталось ввести запрос")
        repeat(1000) { i ->
            memory.record(tool(),"""{"type":"tool","tool":"screen","args":{}}""",
                JSONObject().put("snapshot","snapshot-$i").put("text","x".repeat(12_000)), "image-$i")
        }
        val turns=memory.turns(true)
        assertEquals(4,turns.size)
        assertTrue(turns.sumOf {it.text.length}<16_000)
        assertTrue(turns.last().text.contains("snapshot-999"))
        assertFalse(turns.joinToString {it.text}.contains("snapshot-998"))
        assertTrue(turns[1].text.contains("Осталось ввести запрос"))
        assertEquals(listOf("image-999"),turns.mapNotNull {it.image})
        assertTrue(memory.turns(false).none {it.image!=null})
        assertEquals(1000L,memory.completed)
    }

    @Test fun oldFileContentsDisappearButExplicitNotesSurvive() {
        val memory=AgentContext(emptyList(),"Прочти файл и открой приложение")
        memory.record(tool("read_text"),"{}",JSONObject().put("text","PRIVATE_OLD_FILE_CONTENTS"),null)
        memory.remember("Нужная сумма: 42")
        memory.record(tool(),"{}",JSONObject().put("snapshot","new"),null)
        val text=memory.turns(false).joinToString {it.text}
        assertFalse(text.contains("PRIVATE_OLD_FILE_CONTENTS"));assertTrue(text.contains("Нужная сумма: 42"))
    }

    @Test fun filteringAndDetailedScreenPreserveOriginalNodeIdsAndReportTruncation() {
        val nodes=JSONArray()
        repeat(100) { i -> nodes.put(JSONObject().put("id",i+1).put("text","button-$i").put("description","")
            .put("enabled",true).put("clickable",true).put("editable",false).put("scrollable",false)) }
        val screen=JSONObject().put("snapshot","fresh").put("nodes",nodes)
        val compact=ScreenPayload.compact(screen)
        assertEquals(60,compact.getJSONArray("nodes").length());assertTrue(compact.getBoolean("truncated"))
        assertEquals(100,ScreenPayload.compact(screen,detailed=true).getJSONArray("nodes").length())
        val filtered=ScreenPayload.compact(screen,"button-99").getJSONArray("nodes")
        assertEquals(1,filtered.length());assertEquals(100,filtered.getJSONObject(0).getInt("id"))
        assertTrue(filtered.getJSONObject(0).getBoolean("clickable"));assertFalse(filtered.getJSONObject(0).has("editable"))
        assertTrue(nodes.getJSONObject(0).has("editable")) // original cache payload untouched
        assertEquals(0,ScreenPayload.compact(screen,"absent").getJSONArray("nodes").length())
    }

    @Test fun loopDetectionIgnoresSnapshotUuidButAllowsChangingScreen() {
        val guard=AgentLoopGuard()
        repeat(3) { i -> assertFalse(guard.repeated(tool(args=JSONObject().put("snapshot","s$i")),JSONObject().put("snapshot","s$i").put("text","same"))) }
        assertTrue(guard.repeated(tool(args=JSONObject().put("snapshot","s4")),JSONObject().put("snapshot","s4").put("text","same")))
        repeat(100) { i -> assertFalse(guard.repeated(tool(),JSONObject().put("text","page-$i"))) }
    }

    @Test fun unlimitedModeIsDefaultAndPersistsWithOptionalLegacyLimits() {
        assertTrue(AppSettings.parse(JSONObject()).unlimitedAgent)
        assertTrue(AppSettings.parse(AppSettings().json()).unlimitedAgent)
        assertFalse(AppSettings.parse(AppSettings(unlimitedAgent=false,maxSteps=7).json()).unlimitedAgent)
        assertEquals(7,AppSettings.parse(AppSettings(unlimitedAgent=false,maxSteps=7).json()).maxSteps)
        assertFalse(AgentApprovalPolicy.requiresApproval(tool("remember"),true))
    }
}

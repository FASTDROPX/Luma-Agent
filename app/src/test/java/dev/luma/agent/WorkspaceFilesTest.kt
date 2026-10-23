package dev.luma.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceFilesTest {
    private lateinit var context: Application
    private lateinit var folder: String
    private lateinit var dir: File
    @Before fun setup() {
        TestKeys.install(); context = ApplicationProvider.getApplicationContext(); File(context.noBackupFilesDir, "luma-vault.bin").delete()
        dir = File(context.cacheDir, "workspace-" + System.nanoTime()).apply { mkdirs() }
        folder = TestDocumentsProvider.install(dir).toString()
    }
    @After fun teardown() { TestKeys.restore(); dir.deleteRecursively() }
    private fun tools() = FolderTools(context, folder)
    private fun run(name: String, args: JSONObject): JSONObject = runBlocking { tools().run(name, args) }
    private fun failure(name: String, args: JSONObject): ToolFailure = try { run(name, args); fail("Accepted: $name $args"); throw IllegalStateException() } catch (e: ToolFailure) { e }

    @Test fun textFileIsCreatedDirectlyInTheWorkspaceWithoutListingFirst() {
        val result = run("write_text", JSONObject().put("path", "test.txt").put("text", "Привет"))
        assertEquals(ToolStatus.DONE, result.getString("status")); assertTrue(result.getBoolean("verified"))
        assertEquals("workspace:/test.txt", result.getString("path"))
        assertEquals("Привет", File(TestDocumentsProvider.root, "test.txt").readText())
        // The logical root works the same way, and missing folders are created inside the workspace.
        val nested = run("write_text", JSONObject().put("path", "workspace:/notes/plan.md").put("text", "# План"))
        assertEquals("workspace:/notes/plan.md", nested.getString("path")); assertEquals("workspace:/notes", nested.getJSONArray("createdFolders").getString(0))
        assertEquals("# План", File(TestDocumentsProvider.root, "notes/plan.md").readText())
        assertNotEquals("text/plain", TestDocumentsProvider.createdTypes.last()) // .md is not forced into text/plain (providers would append .txt)
    }

    @Test fun existingFilesAreNotOverwrittenOrLostWithoutExplicitOverwrite() {
        File(TestDocumentsProvider.root, "a.txt").writeText("старое")
        val refused = failure("write_text", JSONObject().put("path", "a.txt").put("text", "новое"))
        assertEquals("exists", refused.code); assertEquals("старое", File(TestDocumentsProvider.root, "a.txt").readText())
        assertTrue(AgentApprovalPolicy.requiresApproval(AgentReply.Tool("write_text", JSONObject().put("path", "a.txt").put("text", "x").put("overwrite", true), ""), confirmActions = false))
        assertEquals(true, run("write_text", JSONObject().put("path", "a.txt").put("text", "новое").put("overwrite", true)).getBoolean("replaced"))
        assertEquals("новое", File(TestDocumentsProvider.root, "a.txt").readText())
        run("append_text", JSONObject().put("path", "a.txt").put("text", "\nещё"))
        assertEquals("новое\nещё", File(TestDocumentsProvider.root, "a.txt").readText())
        File(TestDocumentsProvider.root, "full").mkdirs(); File(TestDocumentsProvider.root, "full/x.txt").writeText("x")
        assertEquals(ToolStatus.DENIED, failure("delete_file", JSONObject().put("path", "full")).status)
        assertTrue(File(TestDocumentsProvider.root, "full/x.txt").exists())
    }

    @Test fun pathsCannotLeaveTheWorkspace() {
        for (path in listOf("../secret.txt", "/sdcard/x.txt", "workspace:/../x.txt", "a/../../b.txt", "content://other/x")) {
            try { run("write_text", JSONObject().put("path", path).put("text", "x")); fail(path) } catch (e: UserError) { }
        }
        assertFalse(File(dir, "secret.txt").exists()); assertEquals(0, TestDocumentsProvider.createdTypes.size)
    }

    @Test fun infoSearchAndFoldersWorkWithoutAFileManager() {
        assertFalse(run("file_info", JSONObject().put("path", "report.txt")).getBoolean("exists"))
        run("create_folder", JSONObject().put("path", "Отчёты/2026"))
        run("write_text", JSONObject().put("path", "Отчёты/2026/report.txt").put("text", "42"))
        val info = run("file_info", JSONObject().put("path", "Отчёты/2026/report.txt"))
        assertTrue(info.toString(), info.getBoolean("exists")); assertEquals(2, info.getInt("bytes"))
        val found = run("search_files", JSONObject().put("query", "REPORT")).getJSONArray("matches")
        assertEquals("workspace:/Отчёты/2026/report.txt", found.getJSONObject(0).getString("path"))
    }

    @Test fun modelSeesALogicalRootWithNameRightsAndNoInventedPath() {
        val info = Workspace.describe(context, folder)
        assertEquals(WorkspaceInfo.READY, info.status); assertEquals("Documents", info.name); assertTrue(info.canRead && info.canWrite)
        assertEquals("Внутренняя память › Documents", info.location)
        val prompt = info.prompt()
        assertTrue(prompt.contains("workspace:/")); assertTrue(prompt.contains("«Documents»")); assertTrue(prompt.contains("запись да"))
        assertFalse(prompt.contains("/storage")); assertFalse(prompt.contains("content://"))
        assertEquals(WorkspaceInfo.NOT_SELECTED, Workspace.describe(context, "").status)
        assertEquals(WorkspaceInfo.LOST, Workspace.describe(context, "content://dev.luma.missing/tree/primary%3AX").status)
    }

    @Test fun agentCreatesTheFileWithOneToolCall() {
        val agent = AgentRun(context, configure = { it.copy(confirmActions = false, folder = folder) }) { i, _ ->
            if (i == 0) Replies.openAiCall("write_text", JSONObject().put("path", "test.txt").put("text", "Создано Luma"))
            else Replies.openAiText("Создал файл workspace:/test.txt.")
        }
        try {
            agent.run("Создай test.txt в своей папке")
            assertEquals(2, agent.requests.size)
            assertEquals("Создано Luma", File(TestDocumentsProvider.root, "test.txt").readText())
            assertTrue((agent.screen as FakeScreen).executed.isEmpty())
            val system = agent.requests[0].body!!.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("workspace:/ = «Documents»"))
        } finally { agent.close() }
    }

    @Test fun missingFolderShowsACardAndCanBeSkippedWithoutOpeningTheScreen() {
        for (missing in listOf("", "content://dev.luma.missing/tree/primary%3AX")) {
            var card = false
            val agent = AgentRun(context, configure = { it.copy(confirmActions = false, folder = missing) }) { step, _ ->
                if (step == 0) Replies.openAiCall("write_text", JSONObject().put("path", "test.txt").put("text", "x"))
                else Replies.openAiText("Выберите папку, чтобы создать файл.")
            }
            val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            watcher.launch { agent.controller.state.collect { state -> if (state.permission != null) {
                assertEquals("workspace", state.permission.id); card = true; agent.controller.skipAccess()
            } } }
            try {
                val messages = agent.run("Создай test.txt")
                assertTrue(card); assertEquals(2, agent.requests.size)
                assertTrue((agent.screen as FakeScreen).executed.isEmpty())
                assertTrue(messages.last().text.contains("папку"))
            } finally { watcher.cancel(); agent.close() }
        }
    }
}

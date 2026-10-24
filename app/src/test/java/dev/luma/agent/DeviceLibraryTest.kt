package dev.luma.agent

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class DeviceLibraryTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        ShadowStatFs.registerStats(File(context.getExternalFilesDir(null),"models"),1_000_000,1_000_000,1_000_000)
        ShadowStatFs.registerStats(File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),"huggingface"),1_000_000,1_000_000,1_000_000)
    }
    @After fun close() {scope.cancel()}
    @Test fun importedModelUsesPrivateCopyAndRemovalKeepsOriginal()=runBlocking {
        val source=File(context.cacheDir,"example.gguf").apply {writeText("GGUFtest")}
        val library=DeviceModels(context,HubDownloads(context),scope)
        library.import(Uri.fromFile(source))
        val item=library.state.value.single()
        assertTrue(item.imported);assertEquals(8,item.bytes)
        val copy=library.file(item.id)
        assertNotEquals(source.canonicalPath,copy.canonicalPath);assertEquals("GGUFtest",copy.readText())
        library.remove(item.id)
        assertTrue(source.exists());assertFalse(copy.exists());assertTrue(library.state.value.isEmpty())
    }
    @Test fun completedHuggingFaceDownloadIsExecutableWithoutAnotherCopy()=runBlocking {
        val downloads=HubDownloads(context);val manager=context.getSystemService(DownloadManager::class.java)
        val library=DeviceModels(context,downloads,scope)
        val id=downloads.enqueue(HubFile("author/model","a".repeat(40),"small.gguf",8),true)
        val request=shadowOf(shadowOf(manager).getRequest(id));val file=File(request.destination.path!!)
        file.parentFile?.mkdirs();file.writeText("GGUFtest");request.status=DownloadManager.STATUS_SUCCESSFUL
        downloads.refresh()
        val item=library.state.value.single()
        assertEquals("hf:$id",item.id);assertFalse(item.imported)
        assertEquals(file.canonicalPath,library.file(item.id).canonicalPath)
    }
    @Test fun rejectsNonGgufAndInvalidIdentifiers()=runBlocking {
        val library=DeviceModels(context,HubDownloads(context),scope)
        val source=File(context.cacheDir,"wrong.gguf").apply {writeText("not gguf")}
        assertTrue(runCatching {library.import(Uri.fromFile(source))}.exceptionOrNull() is UserError)
        assertTrue(library.state.value.isEmpty())
        assertTrue(runCatching {library.file("../../outside")}.exceptionOrNull() is UserError)
    }
}

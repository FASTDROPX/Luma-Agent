package dev.luma.agent

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class HubDownloadTest {
    private lateinit var context: Context
    private lateinit var folder: File
    private lateinit var manager: DownloadManager
    private val file=HubFile("author/model","1".repeat(40),"small.gguf",8)
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext();manager=context.getSystemService(DownloadManager::class.java)
        folder=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"huggingface").apply {mkdirs()}
        ShadowStatFs.registerStats(folder,1_000_000,1_000_000,1_000_000)
    }
    @Test fun downloadsPersistAcrossStoreRecreationAndOnlyVerifiedGgufCanBeShared() = runBlocking {
        val store=HubDownloads(context);val id=store.enqueue(file,true)
        val request=shadowOf(shadowOf(manager).getRequest(id))
        assertEquals(DownloadManager.Request.NETWORK_WIFI,request.allowedNetworkTypes)
        assertFalse(request.allowedOverMetered);assertTrue(request.requestHeaders.isEmpty())
        assertEquals(file.url().toString(),request.uri.toString())
        val downloaded=File(request.destination.path!!);downloaded.writeText("GGUFtest")
        request.status=DownloadManager.STATUS_SUCCESSFUL
        val restored=HubDownloads(context)
        assertEquals(id,restored.state.value.single().id)
        restored.refresh();assertEquals(HubPhase.READY,restored.state.value.single().phase)
        assertEquals(downloaded.canonicalPath,restored.fileForShare(id).canonicalPath)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.model-files",downloaded)
        assertEquals("content",uri.scheme)
        restored.remove(id);assertFalse(downloaded.exists());assertTrue(HubDownloads(context).state.value.isEmpty())
    }
    @Test fun corruptedCompletionRemainsAnErrorAndProviderCannotExposeVault() = runBlocking {
        val store=HubDownloads(context);val id=store.enqueue(file,false)
        val request=shadowOf(shadowOf(manager).getRequest(id));File(request.destination.path!!).writeText("<html>xx")
        request.status=DownloadManager.STATUS_SUCCESSFUL;store.refresh()
        assertEquals(HubPhase.ERROR,store.state.value.single().phase)
        try {store.fileForShare(id);fail()} catch(_:UserError){}
        try {FileProvider.getUriForFile(context,"${context.packageName}.model-files",File(context.noBackupFilesDir,"luma-vault.bin"));fail()} catch(_:IllegalArgumentException){}
    }
    @Test fun insufficientSpaceDuplicateAndConcurrentDownloadLimitsAreEnforced() = runBlocking {
        val store=HubDownloads(context)
        ShadowStatFs.registerStats(folder,1,1,1)
        try {store.enqueue(file,true);fail()} catch(e:UserError){assertTrue(e.message.contains("места"))}
        ShadowStatFs.registerStats(folder,1_000_000,1_000_000,1_000_000)
        store.enqueue(file,true)
        try {store.enqueue(file,true);fail()} catch(e:UserError){assertTrue(e.message.contains("уже"))}
        store.enqueue(file.copy(path="other.gguf"),true)
        try {store.enqueue(file.copy(path="third.gguf"),true);fail()} catch(e:UserError){assertTrue(e.message.contains("две"))}
        assertEquals(2,shadowOf(manager).requestCount)
    }
    @Test fun pendingTransfersReserveSpaceBeforeStartingAnotherLargeFile() = runBlocking {
        val store=HubDownloads(context)
        ShadowStatFs.registerStats(folder,51200,51200,51200)
        val large=file.copy(bytes=100L*1024*1024)
        store.enqueue(large,true)
        try {store.enqueue(large.copy(path="second.gguf"),true);fail()} catch(e:UserError){assertTrue(e.message.contains("текущих загрузок"))}
        assertEquals(1,shadowOf(manager).requestCount)
    }
}

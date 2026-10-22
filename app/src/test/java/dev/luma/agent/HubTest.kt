package dev.luma.agent

import dev.luma.agent.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HubTest {
    private val sha="0123456789abcdef0123456789abcdef01234567"
    @Test fun publicSearchEncodesQueryAndExcludesPrivateAndGatedModels() = runTest {
        val api=HuggingFaceClient { url ->
            assertEquals("huggingface.co",url.host)
            assertEquals("gguf",url.queryParameter("filter"));assertEquals("Qwen + small",url.queryParameter("search"))
            """[{"id":"Qwen/public","downloads":12},{"id":"author/private","private":true},{"id":"author/gated","gated":"manual"},{"id":"../../bad"}]"""
        }
        assertEquals(listOf(HubRepo("Qwen/public",12)),api.search("Qwen + small"))
    }
    @Test fun catalogPinsRevisionAndDownloadsOnlySizedGgufFiles() = runTest {
        val api=HuggingFaceClient { url ->
            assertEquals("/api/models/author/model",url.encodedPath);assertEquals("true",url.queryParameter("blobs"))
            """{"sha":"$sha","siblings":[{"rfilename":"weights/model q4.gguf","size":1024},{"rfilename":"unsafe.pkl","size":1024},{"rfilename":"../escape.gguf","size":1024},{"rfilename":"unknown.gguf"}]}"""
        }
        val file=api.files("author/model").single()
        assertEquals("weights/model q4.gguf",file.path)
        assertEquals("https://huggingface.co/author/model/resolve/$sha/weights/model%20q4.gguf?download=true",file.url().toString())
        assertFalse(file.storageName.contains('/'))
        assertNotEquals(file.storageName,file.copy(repo="other/model").storageName)
        assertNotEquals(file.storageName,file.copy(revision="f".repeat(40)).storageName)
    }
    @Test fun unsafeNamesMutableRevisionsAndExcessiveSizesAreRejected() {
        val valid=HubFile("a/b",sha,"model.gguf",1024)
        listOf(valid.copy(repo="https://evil.com/a"),valid.copy(path="../model.gguf"),valid.copy(path="x\\model.gguf"),valid.copy(path="/model.gguf"),valid.copy(path="model.bin"),valid.copy(revision="main"),valid.copy(bytes=0),valid.copy(bytes=33L*1024*1024*1024)).forEach { file ->
            try { file.url();fail("Accepted: $file") } catch(_:UserError) { }
        }
    }
    @Test fun gatedRepositoryHasClearErrorAndCannotCreateDownload() = runTest {
        val api=HuggingFaceClient { """{"sha":"$sha","gated":"auto","siblings":[]}""" }
        try { api.files("a/b");fail() } catch(e:UserError) { assertTrue(e.message.contains("лицензии")) }
    }
    @Test fun downloadedFilesNeedCorrectMagicAndExactSize() {
        val file=File.createTempFile("model-",".gguf")
        try {
            file.writeBytes("GGUFtest".toByteArray());assertTrue(validGguf(file,8));assertFalse(validGguf(file,9))
            file.writeText("<html>xx");assertFalse(validGguf(file,8))
            file.writeBytes(byteArrayOf(71,71));assertFalse(validGguf(file,2))
        } finally {file.delete()}
    }
}

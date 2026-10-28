package dev.luma.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.luma.agent.core.LlamaNative
import dev.luma.agent.core.NativeProgress
import dev.luma.agent.core.KvCacheType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Manual JNI checks, including a seeded, official SmolLM2-135M-Instruct Q4_K_M GGUF. */
@RunWith(AndroidJUnit4::class)
class NativeModelDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unsupportedArchitectureAndDamagedFileHaveDifferentErrors() {
        LlamaNative.ensureLoaded();LlamaNative.resetCancel()
        val file=File(context.cacheDir,"unsupported-test.gguf")
        val out=ByteArrayOutputStream()
        fun number(n: Long,bytes: Int) {out.write(ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {if(bytes==8) putLong(n) else putInt(n.toInt())}.array())}
        fun string(s: String) {val bytes=s.toByteArray();number(bytes.size.toLong(),8);out.write(bytes)}
        out.write("GGUF".toByteArray());number(3,4);number(0,8);number(1,8)
        string("general.architecture");number(8,4);string("qwen3asr")
        while(out.size()%32!=0) out.write(0)
        try {
            file.writeBytes(out.toByteArray())
            fun failure(): String {
                try {val h=LlamaNative.load(file.path.toByteArray());LlamaNative.free(h);fail("Invalid model loaded")} catch(e: IllegalStateException) {return e.message.orEmpty()}
                error("Expected native exception")
            }
            assertEquals("LUMA_ARCH",failure())
            file.writeText("not a GGUF")
            // Diagnostics from the previous load must not leak into this one.
            assertEquals("LUMA_MODEL",failure())
        } finally {file.delete()}
    }
    @Test fun textModelStillLoadsAndGeneratesThroughJni() {
        val file=File(context.getExternalFilesDir(null),"smollm2-test.gguf")
        assertEquals("Seed the pinned test GGUF before running this manual test",105454432L,file.length())
        LlamaNative.ensureLoaded();LlamaNative.resetCancel()
        val handle=LlamaNative.load(file.path.toByteArray())
        assertTrue(handle!=0L)
        try {
            val pairs=listOf(KvCacheType.F16 to KvCacheType.F16,KvCacheType.Q8_0 to KvCacheType.Q8_0,
                KvCacheType.Q5_0 to KvCacheType.Q4_0,KvCacheType.IQ4_NL to KvCacheType.Q4_0,KvCacheType.Q5_0 to KvCacheType.IQ4_NL,
                KvCacheType.Q4_0 to KvCacheType.Q5_0,KvCacheType.IQ4_NL to KvCacheType.IQ4_NL)
            for((k,v) in pairs) {
                val reply=LlamaNative.generate(handle,arrayOf("user".toByteArray()),arrayOf("Hello!".toByteArray()),512,12,0f,.95f,2,k.nativeCode,v.nativeCode,NativeProgress {_,_,_,_->})
                assertTrue("$k/$v: prompt",reply.input>0);assertTrue("$k/$v: output",reply.output>0)
                assertTrue("$k/$v: text",reply.text.toString(Charsets.UTF_8).isNotBlank())
            }
            try {
                LlamaNative.generate(handle,arrayOf("user".toByteArray()),arrayOf("Hello!".toByteArray()),512,12,0f,.95f,2,99,0,NativeProgress {_,_,_,_->})
                fail("Invalid JNI cache type accepted")
            } catch(e: IllegalStateException) {assertEquals("LUMA_KV_CACHE",e.message)}
        } finally {LlamaNative.free(handle)}
    }
}

package dev.luma.agent

import dev.luma.agent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderRecoveryTest {
    private val c=Connection("fake-key","gemini-test")
    private fun answer()=JSONObject().put("candidates",JSONArray().put(JSONObject().put("content",JSONObject().put("parts",JSONArray().put(JSONObject().put("text","ok"))))))
    @Test fun temporary503RetriesExactlyTheSameModelRequestAndThenReturns()=runTest {
        var calls=0;val bodies=mutableListOf<String>();val retries=mutableListOf<Long>()
        val client=ProviderClient {r->calls++;bodies+=r.body.toString();if(calls<3) throw ProviderClient.httpError(503);answer()}
        assertEquals("ok",client.generate(Provider.GEMINI,c,listOf(Turn("user","hello")),"system",true) {_,delay->retries+=delay})
        assertEquals(3,calls);assertEquals(1,bodies.distinct().size);assertEquals(listOf(1000L,2000L),retries)
    }
    @Test fun badKeyAndMissingModelAreNeverRetried()=runTest {
        for(code in listOf(401,403,404)) {
            var calls=0
            val client=ProviderClient {calls++;throw ProviderClient.httpError(code)}
            try {client.generate(Provider.GEMINI,c,listOf(Turn("user","x")),"system");fail()} catch(e: UserError) {assertTrue(e is ProviderFailure)}
            assertEquals(1,calls)
        }
    }
    @Test fun retryAfterIsBoundedAndCancelStopsBackoffBeforeAnotherRequest()=runTest {
        var calls=0
        val client=ProviderClient {calls++;throw ProviderClient.httpError(503,"90")}
        val job=launch {client.generate(Provider.GEMINI,c,listOf(Turn("user","x")),"system")}
        runCurrent();assertEquals(1,calls);advanceTimeBy(7000);assertEquals(1,calls)
        job.cancelAndJoin();advanceUntilIdle();assertEquals(1,calls)
        assertEquals(8000L,(ProviderClient.httpError(503,"90") as ProviderFailure).waitMillis)
    }
}

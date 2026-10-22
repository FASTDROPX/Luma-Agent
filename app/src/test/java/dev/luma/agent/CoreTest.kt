package dev.luma.agent

import dev.luma.agent.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid input accepted") } catch (_: UserError) { } }
    @Test fun protocolAllowsOnlyKnownCommandsAndExactFinalFormat() {
        val tool = AgentProtocol.parse("{\"type\":\"tool\",\"tool\":\"screen\",\"args\":{},\"message\":\"Читаю\"}") as AgentReply.Tool
        assertEquals("screen",tool.name)
        assertEquals("Готово",(AgentProtocol.parse("```json\n{\"type\":\"final\",\"text\":\"Готово\"}\n```") as AgentReply.Final).text)
        rejects { AgentProtocol.parse("{\"type\":\"tool\",\"tool\":\"shell\",\"args\":{\"cmd\":\"x\"}}") }
        rejects { AgentProtocol.parse("{\"type\":\"final\",\"text\":\"ok\",\"tool\":\"delete_file\"}") }
        rejects { AgentProtocol.parse("{\"type\":\"tool\",\"tool\":\"tap\",\"args\":[]}") }
        rejects { AgentProtocol.parse("Just click something") }
        rejects { AgentProtocol.parse("{\"type\":\"final\",\"text\":\"ok\"}{\"type\":\"tool\"}") }
    }
    @Test fun protocolToleratesCommonModelFormatSlipsButNotAmbiguity() {
        fun tool(raw: String) = AgentProtocol.parse(raw) as AgentReply.Tool
        assertEquals("home",tool("""[{"type":"tool","tool":"home","args":{}}]""").name)
        assertEquals("home",tool("""{"tool":"home","args":{}}""").name)
        assertEquals("home",tool("""{"type":"home","args":{}}""").name)
        assertEquals("home",tool("""{"type":"tool_call","name":"home"}""").name)
        assertEquals("новости",tool("""Вот команда: {"type":"action","tool":"set_text","arguments":"{\"snapshot\":\"s\",\"node\":3,\"text\":\"новости\"}"}""").args.getString("text"))
        assertEquals("home",tool("<think>надо домой</think>\n```json\n{\"type\":\"tool\",\"tool\":\"home\",\"args\":{}}\n```").name)
        assertEquals("Готово",(AgentProtocol.parse("""{"type":"answer","message":"Готово"}""") as AgentReply.Final).text)
        assertEquals("Готово",(AgentProtocol.parse("""{"text":"Готово"}""") as AgentReply.Final).text)
        // A whole plan: only the first step runs, the rest is counted and dropped.
        assertEquals(1,tool("""[{"type":"tool","tool":"home"},{"type":"tool","tool":"back"}]""").skipped)
        val plan=tool("""{"type":"home","message":"Returning to home screen"}{"type":"tool","tool":"open_app","args":{"package":"com.android.chrome"}}""")
        assertEquals("home",plan.name);assertEquals(1,plan.skipped)
        assertEquals(0,tool("""{"type":"tool","tool":"home","args":{}}""").skipped)
        rejects { AgentProtocol.parse("""{"type":"final","text":"Готово"},{"type":"tool","tool":"home"}""") }
        rejects { AgentProtocol.parse("""{"type":"maybe","x":1}""") }
        try { AgentProtocol.parse("""{"kind":"weird"}"""); fail() } catch (e: UserError) { assertTrue(e.message.contains("weird")) }
    }
    @Test fun filePathsCannotEscapeSelectedRoot() {
        assertEquals(listOf("Учёба","заметка.txt"), relativeParts("Учёба/заметка.txt"))
        listOf("../x","a/../../b","/sdcard/x","content://secret","a\\b","a/./x","a\u0000b").forEach { rejects { relativeParts(it) } }
    }
    @Test fun localEndpointsCannotHideCredentialsOrMasqueradeAsPrivateHosts() {
        assertEquals("http://192.168.1.10:11434/v1/",validateEndpoint("http://192.168.1.10:11434/v1"))
        assertEquals("https://example.com/v1/",validateEndpoint("https://example.com/v1/"))
        listOf("http://evil.example/v1","http://192.168.evil.com/v1","http://10.attacker.com/v1","https://key@example.com/v1","https://example.com/v1?key=secret","file:///tmp/x").forEach { rejects { validateEndpoint(it) } }
    }
    @Test fun actionIdsRequireIntegersInsteadOfStringsOrFractions() {
        assertEquals(12,JSONObject("{\"node\":12}").requiredInt("node"))
        rejects { JSONObject("{\"node\":\"12\"}").requiredInt("node") }
        rejects { JSONObject("{\"node\":12.5}").requiredInt("node") }
    }
    @Test fun fileReadsHaveHardByteLimitEvenWhenMetadataLies() {
        assertEquals("abc",readLimited("abc".byteInputStream(),3).toString(Charsets.UTF_8))
        rejects{readLimited(ByteArray(100_000){1}.inputStream(),65_536)}
    }
    @Test fun settingsRoundTripKeepsProvidersAndChoicesSeparate() {
        val settings=AppSettings(onboarded=true,provider=Provider.OPENROUTER,connections=mapOf(
            Provider.GEMINI to Connection("fake-google-key","gemini-test",models=listOf(ModelInfo("gemini-test","Test",true))),
            Provider.OPENROUTER to Connection("fake-router-key","vendor/model")),screenshots=true,panel=false,folder="content://example/folder")
        assertEquals(settings,AppSettings.parse(settings.json()).copy(connections=AppSettings.parse(settings.json()).connections.filterValues { it != Connection() }))
    }
    @Test fun geminiCatalogUsesHeaderPaginationAndFiltersSpecializedModels() = runTest {
        var count=0
        val api=ProviderClient { request ->
            count++;assertEquals("test-key",request.headers["x-goog-api-key"]);assertFalse(request.url.contains("test-key"));assertTrue(request.catalog)
            if(count==1)JSONObject("""{"models":[{"name":"models/gemini-good","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-image","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"a+b"}""")
            else { assertTrue(request.url.endsWith("pageToken=a%2Bb"));JSONObject("""{"models":[{"name":"models/gemini-other","supportedGenerationMethods":["generateContent"]}]}""") }
        }
        assertEquals(listOf("gemini-good","gemini-other"),api.models(Provider.GEMINI,Connection("test-key")).map{it.id});assertEquals(2,count)
    }
    @Test fun openRouterAuthenticatesBeforeUsingUserCatalog() = runTest {
        val urls=mutableListOf<String>()
        val api=ProviderClient { request ->
            urls+=request.url;assertEquals("Bearer test-key",request.headers["Authorization"])
            if(urls.size==1)JSONObject("{\"data\":{}}") else JSONObject("""{"data":[{"id":"v/model","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}}]}""")
        }
        assertTrue(api.models(Provider.OPENROUTER,Connection("test-key")).single().vision)
        assertEquals(listOf("https://openrouter.ai/api/v1/key","https://openrouter.ai/api/v1/models/user"),urls)
    }
    @Test fun anthropicAndGeminiReceiveRealImagePartsWithoutKeysInPayload() = runTest {
        for(p in listOf(Provider.CLAUDE,Provider.GEMINI)) {
            val api=ProviderClient { request ->
                val body=request.body!!.toString();assertFalse(body.contains("private-key"));assertTrue(body.contains("aW1hZ2U="))
                if(p==Provider.CLAUDE){assertTrue(body.contains("media_type"));assertEquals("private-key",request.headers["x-api-key"]);JSONObject("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")}
                else {assertTrue(body.contains("inlineData"));JSONObject("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}""")}
            }
            assertEquals("ok",api.generate(p,Connection("private-key","gemini-test"),listOf(Turn("user","screen","aW1hZ2U=")),"system",true))
        }
    }
    @Test fun openAIStyleUsesTheSavedModelAndUntruncatedContent() = runTest {
        for(p in listOf(Provider.OPENAI,Provider.OPENROUTER,Provider.DEEPSEEK,Provider.LOCAL)){
            val api=ProviderClient { request ->
                assertEquals("chosen-model",request.body!!.getString("model"));assertTrue(request.url.endsWith("chat/completions"))
                assertEquals(p==Provider.LOCAL,request.local) // only local servers get the long thinking timeout
                JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}""")
            }
            assertEquals("answer",api.generate(p,Connection("test-key","chosen-model","http://127.0.0.1:11434/v1/"),listOf(Turn("user","question")),"system"))
        }
    }
    @Test fun stoppedGenerationCancelsInsteadOfRetrying() = runTest {
        var count=0;val entered=CompletableDeferred<Unit>()
        val api=ProviderClient { count++;entered.complete(Unit);awaitCancellation() }
        val job=launch{api.generate(Provider.DEEPSEEK,Connection("key","model"),listOf(Turn("user","q")),"system")}
        entered.await();job.cancelAndJoin();assertEquals(1,count)
    }
    @Test fun blockedWordsComeOnlyFromTheUserListAndPersist() {
        assertNull(BlockedWords.match("","Оплатить заказ"))
        assertEquals(listOf("оплатить","Перевести","удалить аккаунт"),BlockedWords.parse(" оплатить, Перевести;\nудалить аккаунт ,, оплатить"))
        assertEquals("перевести",BlockedWords.match("купить, перевести","Нажать: ПЕРЕВЕСТИ 500 ₽"))
        assertNull(BlockedWords.match("удалить аккаунт","Нажать: Удалить фото"))
        assertEquals("оплатить, перевести",AppSettings.parse(AppSettings(blockedWords="оплатить, перевести").json()).blockedWords)
        assertEquals("",AppSettings.parse(JSONObject()).blockedWords)
        assertEquals(BlockedWords.MAX_LENGTH,AppSettings.parse(JSONObject().put("blockedWords","x".repeat(5000))).blockedWords.length)
    }
    @Test fun lanHostsIncludeLinkLocalAndPrivateIpv6ButNotLookalikeNames() {
        assertEquals("http://169.254.10.2:8080/v1/",validateEndpoint("http://169.254.10.2:8080/v1"))
        assertEquals("http://[fd12::5]:11434/v1/",validateEndpoint("http://[fd12::5]:11434/v1"))
        assertTrue(isPrivateHost("fe80::1"));assertTrue(isPrivateHost("::1"));assertTrue(isPrivateHost("printer.local"))
        listOf("fd.example.com","fe80.example.com","2001:db8::1","8.8.8.8","169.255.0.1").forEach { assertFalse(it,isPrivateHost(it)) }
    }
    @Test fun clientRefusesPlainHttpToPublicHostsBeforeAnyConnection() {
        val client=okhttp3.OkHttpClient.Builder().addInterceptor(localCleartextOnly).build()
        try {client.newCall(okhttp3.Request.Builder().url("http://example.com/v1/models").build()).execute();fail()} catch(e: java.io.IOException) {assertTrue(e is CleartextBlocked)}
        // A local host passes the check; the closed port then fails as an ordinary network error.
        try {client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:9/").build()).execute().close()} catch(e: java.io.IOException) {assertFalse(e is CleartextBlocked)}
    }
    @Test fun sizeErrorsNameTheActualLimit() {
        try {readLimited(ByteArray(9_000_000).inputStream(),8_000_000);fail()} catch(e: UserError) {assertEquals("Файл больше 8 МБ.",e.message)}
        try {readLimited(ByteArray(70_000).inputStream(),65_536);fail()} catch(e: UserError) {assertEquals("Файл больше 64 КБ.",e.message)}
        try {readLimited(ByteArray(10).inputStream(),5,"Свой текст");fail()} catch(e: UserError) {assertEquals("Свой текст",e.message)}
    }
    @Test fun truncationIsAnErrorAndNeverBecomesAnAgentCommand() = runTest {
        val api=ProviderClient { JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""") }
        try{api.generate(Provider.DEEPSEEK,Connection("key","model"),listOf(Turn("user","q")),"system",true);fail()}catch(e:UserError){assertTrue(e.message.contains("обрезан"))}
    }
}

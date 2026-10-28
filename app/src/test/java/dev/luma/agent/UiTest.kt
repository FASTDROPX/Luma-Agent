package dev.luma.agent

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import dev.luma.agent.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=LumaApp::class,qualifiers="w411dp-h891dp-mdpi",shadows=[HostAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var graph: AppGraph
    @Before fun setup(){I18n.choose("ru");TestKeys.install();graph=(ApplicationProvider.getApplicationContext<LumaApp>()).graph;runBlocking{graph.vault.update{it.copy(reducedMotion=true,dynamicColors=false,theme="dark",language="ru")}}}
    @After fun teardown(){graph.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel();TestKeys.restore()}
    @Test fun voiceAgentCardStaysInsideConversation() {
        runBlocking {graph.vault.update {it.copy(onboarded=true)}}
        graph.voice.state.value=VoiceState("conversation","Думаю","Открой контакты")
        graph.voice.screen.value=true
        @Suppress("UNCHECKED_CAST")
        val state=AgentController::class.java.getDeclaredField("mutable").apply {isAccessible=true}.get(graph.agent) as kotlinx.coroutines.flow.MutableStateFlow<RunState>
        state.value=RunState(active=true,acting=true,stage="Открываю контакты",startedAt=1)
        compose.setContent {AppRoot(graph)}
        compose.onNodeWithTag("conversation-screen").assertIsDisplayed()
        compose.onNodeWithTag("conversation-run-card").assertIsDisplayed()
        compose.onNodeWithText("Luma работает").assertIsDisplayed()
        compose.onNodeWithText("Открываю контакты").assertIsDisplayed()
        assertTrue(graph.voice.screen.value)
    }
    @Test fun localCachePickersSaveDifferentKAndVTypes() {
        compose.setContent {val settings by graph.vault.state.collectAsStateWithLifecycle();LumaTheme(settings) {DeviceModelsScreen(graph)}}
        compose.onNodeWithTag("device-models").performScrollToNode(hasTestTag("kv-cache-k"))
        compose.onNodeWithTag("kv-cache-k").performClick()
        compose.onNodeWithTag("kv-cache-k-q5_0").performClick()
        compose.waitUntil(5000) {graph.vault.state.value.localCacheK==KvCacheType.Q5_0}
        compose.onNodeWithTag("device-models").performScrollToNode(hasTestTag("kv-cache-v"))
        compose.onNodeWithTag("kv-cache-v").performClick()
        compose.onNodeWithTag("kv-cache-v-iq4_nl").performClick()
        compose.waitUntil(5000) {graph.vault.state.value.localCacheV==KvCacheType.IQ4_NL}
        assertEquals(KvCacheType.Q5_0,graph.vault.state.value.localCacheK)
    }
    @Test fun composerHasTwoModesAndOptionsStayInASheet() {
        runBlocking {graph.vault.update {it.copy(onboarded=true)}}
        compose.setContent {AppRoot(graph)}
        compose.onAllNodesWithTag("chat-web-search").assertCountEquals(0)
        compose.onAllNodesWithTag("chat-deep-thinking").assertCountEquals(0)
        compose.onNodeWithTag("chat-options").performClick()
        compose.onNodeWithTag("chat-web-search").assertIsDisplayed().performClick()
        compose.waitUntil(5000) {graph.vault.state.value.webSearch}
        compose.onNodeWithTag("chat-deep-thinking").assertIsDisplayed()
    }
    @Test fun accessAndOnboardingBothOfferTheBatchRequest() {
        compose.setContent {val settings by graph.vault.state.collectAsStateWithLifecycle();LumaTheme(settings) {AccessScreen(graph)}}
        compose.onNodeWithTag("request-all-access").assertIsDisplayed().performClick()
        compose.onNodeWithText("Запросить все доступы?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onAllNodesWithTag("optional-access-all").assertCountEquals(0)
    }
    @Test fun onboardingExplainsAppAndOffersExplicitClipboardAction() {
        compose.setContent{AppRoot(graph)}
        compose.onNodeWithText("Чат с ИИ\nи помощь на телефоне").assertIsDisplayed()
        screenshot("01-welcome-dark")
        compose.onNodeWithText("Настроить доступ").performClick()
        compose.onNodeWithText("Доступ для агента").assertIsDisplayed()
        screenshot("02-permissions-dark")
        compose.onNodeWithText("Подключить модель").performClick()
        compose.onNodeWithText("Подключение модели").assertIsDisplayed()
        compose.onNodeWithContentDescription("Вставить из буфера").assertIsDisplayed()
        compose.onNodeWithText("Сохранить ключ").assertIsDisplayed()
        screenshot("02-connect-dark")
    }
    @Test @Config(qualifiers="w320dp-h568dp-mdpi")
    fun allThreeSetupPagesFitSmallScreenWithLargerText() {
        compose.setContent { val density=LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) { AppRoot(graph) } }
        screenshot("07-small-welcome-130")
        contained("setup-next","setup")
        containedText("Чат с ИИ\nи помощь на телефоне","setup-welcome")
        containedText("Доступ включаешь ты","setup-welcome")
        screenshot("07-small-welcome-130")
        compose.onNodeWithTag("setup-next").performClick()
        listOf("request-all-access","setup-screen-access","setup-folder-access","setup-notification-access","setup-access-options").forEach { compose.onNodeWithTag(it).performScrollTo(); contained(it,"setup-access") }
        containedText("Доступ для агента","setup-access")
        screenshot("08-small-permissions-130")
        compose.onNodeWithTag("setup-screen-access").performClick()
        compose.onNodeWithText("Включить управление").assertIsDisplayed()
        compose.onNodeWithText("Позже").performClick()
        compose.onNodeWithTag("setup-access-options").performClick()
        compose.onNodeWithText("Снимки для модели").assertIsDisplayed()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("setup-next").performClick()
        listOf("setup-provider","setup-key","setup-save","setup-model").forEach { contained(it,"setup-connection") }
        compose.onNodeWithTag("setup-next").assertIsNotEnabled()
        screenshot("09-small-connection-130")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
        compose.onNodeWithTag("setup-provider").performClick()
        compose.onNodeWithText("Локальный сервер").performClick()
        compose.waitUntil(5000){graph.vault.state.value.provider==Provider.LOCAL}
        contained("setup-endpoint","setup-connection")
        contained("setup-model","setup-connection")
        screenshot("10-small-local-130")
    }
    @Test @Config(qualifiers="w360dp-h640dp-mdpi")
    fun emptyChatAndAgentComposerFitWithoutScrolling() {
        runBlocking{graph.vault.update{it.copy(onboarded=true)}}
        compose.setContent { val density=LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) { AppRoot(graph) } }
        listOf("suggest-idea","suggest-app","suggest-files","chat-connect").forEach{contained(it,"chat-welcome")}
        contained("chat-input","chat-composer")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyAncestor(hasTestTag("chat-welcome"))).assertCountEquals(0)
        screenshot("11-compact-chat-130")
        compose.onNodeWithTag("suggest-app").performClick()
        compose.onNodeWithText("Найди и открой приложение ").assertExists()
        contained("chat-input","chat-composer")
        listOf("suggest-idea","suggest-app","suggest-files","chat-connect").forEach{contained(it,"chat-welcome")}
        screenshot("12-compact-agent-130")
    }
    @Test @Config(qualifiers="w360dp-h640dp-mdpi")
    fun setupRestoresCatalogAndAppliesChosenModelOnThirdPage() {
        runBlocking {
            graph.vault.saveConnection(Provider.GEMINI,"demo-not-a-real-key","")
            graph.vault.saveModels(Provider.GEMINI,graph.vault.state.value.connection(),listOf(ModelInfo("first","Первая модель",true),ModelInfo("second","Вторая модель",true)))
        }
        compose.setContent{AppRoot(graph)}
        compose.onNodeWithTag("setup-next").performClick()
        compose.onNodeWithTag("setup-skip").performClick()
        compose.onNodeWithTag("setup-model").performClick()
        compose.onNodeWithText("Вторая модель").performClick()
        compose.waitUntil(5000){graph.vault.state.value.connection().model=="second"}
        compose.onNodeWithTag("setup-next").assertIsEnabled()
        screenshot("13-connected-setup")
        compose.onNodeWithTag("setup-next").performClick()
        compose.waitUntil(5000){graph.vault.state.value.onboarded}
        compose.waitForIdle()
        compose.onNodeWithText("Привет, я Luma").assertIsDisplayed()
        assertTrue(graph.vault.state.value.onboarded)
    }
    @Test fun mainChatNavigationAndAccessGuideAreSeparate() {
        runBlocking{graph.vault.update{it.copy(onboarded=true)}}
        compose.setContent{AppRoot(graph)}
        compose.onNodeWithText("Привет, я Luma").assertExists()
        screenshot("03-chat-dark")
        compose.onNodeWithText("Доступ",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Разрешения агента").assertExists()
        compose.onNodeWithText("Как включить").performClick()
        compose.onNodeWithText("Включить управление").assertExists()
        compose.onNodeWithText("Позже").performClick()
        screenshot("04-access-dark")
    }
    @Test fun lightThemeAndModelSelectionRenderWithSavedCatalog() {
        runBlocking {
            graph.vault.saveConnection(Provider.GEMINI,"demo-not-a-real-key","")
            graph.vault.saveModels(Provider.GEMINI,graph.vault.state.value.connection(),listOf(ModelInfo("gemini-demo","Gemini · пример",true),ModelInfo("gemini-other","Другая модель",true)))
            graph.vault.selectModel(Provider.GEMINI,"gemini-demo")
            graph.vault.update{it.copy(onboarded=true,theme="light")}
        }
        compose.setContent{AppRoot(graph)}
        screenshot("05-chat-light")
        compose.onNodeWithText("Модели",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Модель").performScrollTo()
        screenshot("06-models-light")
        compose.onNodeWithText("Gemini · пример").performClick()
        compose.onNodeWithText("Выбери модель").assertExists()
        compose.onNodeWithText("Другая модель").performClick()
        compose.waitUntil(5000){graph.vault.state.value.connection().model=="gemini-other"}
    }
    @Test fun welcomeIconFliesInAndSettlesBeforeAllTilesAreShown() {
        runBlocking {graph.vault.update {it.copy(reducedMotion=false)}}
        compose.mainClock.autoAdvance=false
        compose.setContent {AppRoot(graph)}
        compose.mainClock.advanceTimeBy(200)
        val start=compose.onNodeWithTag("luma-flying").getUnclippedBoundsInRoot().left
        screenshot("14-intro-in-flight")
        compose.mainClock.advanceTimeBy(450)
        screenshot("36-intro-particles-arriving")
        compose.mainClock.advanceTimeBy(250)
        screenshot("37-intro-particles-catching-up")
        compose.mainClock.advanceTimeBy(1500)
        val end=compose.onNodeWithTag("luma-flying").getUnclippedBoundsInRoot().left
        screenshot("15-intro-settled")
        assertTrue("Icon did not move from the right: $start -> $end",start>end+30.dp)
        compose.onNodeWithText("Привет, я Luma").assertIsDisplayed()
        containedText("Доступ включаешь ты","setup-welcome")
        compose.mainClock.autoAdvance=true
    }
    @Test fun huggingFaceCatalogOffersSizedFilesAndExplicitWifiDownloadConfirmation() {
        val sha="1".repeat(40)
        val client=HuggingFaceClient {url -> if(url.queryParameter("blobs")=="true") """{"sha":"$sha","siblings":[{"rfilename":"small-Q4.gguf","size":600000000}]}""" else """[{"id":"author/Small-GGUF","downloads":120}]""" }
        val theme=graph.vault.state.value
        compose.setContent {LumaTheme(theme){androidx.compose.material3.Surface(color=androidx.compose.material3.MaterialTheme.colorScheme.background){HuggingFaceScreen(graph,client)}}}
        compose.waitUntil(5000){compose.onAllNodesWithText("author/Small-GGUF").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("author/Small-GGUF").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("small-Q4.gguf").fetchSemanticsNodes().isNotEmpty()}
        screenshot("16-huggingface-files")
        compose.onNodeWithText("Скачать").performClick()
        compose.onNodeWithText("Скачать модель?").assertIsDisplayed()
        compose.onNodeWithText("Только Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag("hub-library").performClick()
        compose.onNodeWithText("Пока пусто. Найди модель в каталоге и выбери файл GGUF.").assertIsDisplayed()
        screenshot("17-huggingface-downloads")
    }
    @Test fun sphereIgnoresTouchesAndSatellitesContinueTheirOrbits() {
        val theme=graph.vault.state.value.copy(reducedMotion=false)
        val scene=OrbPhysics()
        compose.mainClock.autoAdvance=false
        compose.setContent {LumaTheme(theme) {Surface(Modifier.fillMaxSize()) {Box(contentAlignment=Alignment.Center) {LumaOrb(Modifier.size(300.dp),simulation=scene)}}}}
        compose.mainClock.advanceTimeBy(100)
        screenshot("18-orbit-idle")
        compose.onNodeWithTag("luma-orb").assertHasNoClickAction()
        val before=scene.particles.map {Triple(it.x,it.y,it.z)}
        val time=scene.time
        compose.onNodeWithTag("luma-orb").performTouchInput {click()}
        val reference=OrbPhysics();var remaining=scene.time
        while(remaining>.0000001) {val dt=minOf(.05,remaining);reference.advance(dt.toFloat());remaining-=dt}
        scene.particles.indices.forEach {i->assertEquals(reference.particles[i].x,scene.particles[i].x,.0001f);assertEquals(reference.particles[i].z,scene.particles[i].z,.0001f)}
        compose.mainClock.advanceTimeBy(500)
        assertNotEquals(before,scene.particles.map {Triple(it.x,it.y,it.z)})
        screenshot("19-orbit-moving")
        compose.mainClock.advanceTimeBy(6000);screenshot("20-orbit-behind-sphere")
        compose.mainClock.autoAdvance=true
    }
    @Test fun reducedMotionKeepsOrbitStaticAndSphereIsNeverClickable() {
        val theme=graph.vault.state.value.copy(reducedMotion=true)
        val scene=OrbPhysics()
        compose.setContent {LumaTheme(theme) {Surface(Modifier.fillMaxSize()) {Box(contentAlignment=Alignment.Center) {LumaOrb(Modifier.size(240.dp),simulation=scene,interactive=true)}}}}
        val positions=scene.particles.map {Triple(it.x,it.y,it.z)}
        compose.onNodeWithTag("luma-orb").assertHasNoClickAction().performTouchInput {swipeLeft()}
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(positions,scene.particles.map {Triple(it.x,it.y,it.z)})
    }
    @Test fun homeSphereSupportsRotationAndSevenSecondHoldWithoutMovingItsCentre() {
        val theme=graph.vault.state.value.copy(reducedMotion=false)
        val scene=OrbPhysics()
        compose.mainClock.autoAdvance=false
        compose.setContent {LumaTheme(theme) {Surface(Modifier.fillMaxSize()) {Box(contentAlignment=Alignment.Center) {LumaOrb(Modifier.size(300.dp),simulation=scene,interactive=true)}}}}
        compose.mainClock.advanceTimeBy(100)
        val before=compose.onNodeWithTag("luma-orb").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("luma-orb").performTouchInput {down(center);moveBy(Offset(44f,12f),delayMillis=80);up()}
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        assertTrue(scene.yaw>.15f)
        assertEquals(before,compose.onNodeWithTag("luma-orb").getUnclippedBoundsInRoot())
        screenshot("33-orb-rotated")
        compose.onNodeWithTag("luma-orb").performTouchInput {down(center)}
        compose.mainClock.advanceTimeBy(7000);compose.waitForIdle()
        assertTrue(scene.charge in .985f.. .997f);assertTrue(scene.orbitScale>1.31f)
        screenshot("34-orb-hold-seven-seconds")
        compose.onNodeWithTag("luma-orb").performTouchInput {advanceEventTime(7000);up()}
        assertFalse(scene.isPressed)
        compose.mainClock.advanceTimeBy(10000);compose.waitForIdle()
        assertEquals(0f,scene.charge,.001f);assertEquals(0f,scene.yaw,.001f)
        screenshot("35-orb-released")
        compose.mainClock.autoAdvance=true
    }
    @Test fun cancelledHoldAndTouchOutsideBodyDoNotTrapTheGesture() {
        val theme=graph.vault.state.value.copy(reducedMotion=false)
        val scene=OrbPhysics()
        compose.mainClock.autoAdvance=false
        compose.setContent {LumaTheme(theme) {LumaOrb(Modifier.size(300.dp),simulation=scene,interactive=true)}}
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("luma-orb").performTouchInput {down(Offset(4f,4f))}
        compose.mainClock.advanceTimeBy(2000);compose.waitForIdle()
        assertFalse(scene.isPressed);assertEquals(0f,scene.charge,0f)
        compose.onNodeWithTag("luma-orb").performTouchInput {up();down(center)}
        compose.mainClock.advanceTimeBy(2000);compose.waitForIdle();assertTrue(scene.charge>.6f)
        compose.onNodeWithTag("luma-orb").performTouchInput {cancel()}
        assertFalse(scene.isPressed)
        compose.mainClock.advanceTimeBy(4000);compose.waitForIdle();assertEquals(0f,scene.charge,.001f)
        compose.mainClock.autoAdvance=true
    }
    @Test fun settingsSectionsPersistEffectsAndIndependentModelInstructions() {
        runBlocking {
            graph.vault.saveConnection(Provider.GEMINI,"test-key","")
            graph.vault.saveModels(Provider.GEMINI,graph.vault.state.value.connection(),listOf(ModelInfo("gemini-test")))
            graph.vault.selectModel(Provider.GEMINI,"gemini-test")
        }
        compose.setContent {val settings by graph.vault.state.collectAsStateWithLifecycle();LumaTheme(settings) {Surface {SettingsScreen(graph)}}}
        screenshot("21-settings-home")
        compose.onNodeWithText("Эффекты").performClick()
        compose.onNodeWithText("Motion blur").assertIsDisplayed()
        // Toggle via the row's switch; scrolling is expected inside settings.
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Motion blur"))
        compose.onNodeWithTag("toggle-Motion blur").performClick()
        compose.waitUntil(5000) {graph.vault.state.value.visuals.motionBlur}
        screenshot("22-settings-effects")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Все настройки"))
        compose.onNodeWithText("Все настройки").performClick()
        compose.onNodeWithText("Оформление").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Лаванда"));compose.onNodeWithText("Лаванда").performClick()
        compose.waitUntil(5000) {!graph.vault.state.value.dynamicColors && graph.vault.state.value.visuals.palette=="lilac"}
        screenshot("23-settings-palette")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Все настройки"));compose.onNodeWithText("Все настройки").performClick()
        compose.onNodeWithText("Параметры модели").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("model-instructions"))
        compose.onNodeWithTag("model-instructions").performTextInput("Отвечай кратко")
        compose.onNodeWithText("Сохранить промпт").performScrollTo().performClick()
        compose.waitUntil(5000) {graph.vault.state.value.connection().generation().instruction=="Отвечай кратко"}
        val savedVault=SettingsVault(compose.activity)
        assertNull(savedVault.loadError,savedVault.loadError)
        assertEquals("Отвечай кратко",savedVault.state.value.connection().generation().instruction)
        screenshot("24-settings-model")
    }
    @Test @Config(qualifiers="w360dp-h640dp-mdpi")
    fun chatAccessModesPersistAndSystemPromptLivesOnlyInModelSettings() {
        runBlocking {
            graph.vault.update {it.copy(onboarded=true)}
            graph.vault.saveConnection(Provider.GEMINI,"fake-key","")
            graph.vault.saveModels(Provider.GEMINI,graph.vault.state.value.connection(),listOf(ModelInfo("gemini-test")))
            graph.vault.selectModel(Provider.GEMINI,"gemini-test")
        }
        compose.setContent {AppRoot(graph)}
        contained("agent-access-mode","chat-composer")
        compose.onNodeWithTag("agent-access-mode").performClick()
        compose.onNodeWithTag("access-mode-full").performClick()
        compose.onNodeWithTag("confirm-full-access").assertIsDisplayed()
        assertTrue(graph.vault.state.value.confirmActions)
        compose.onNodeWithText("Отмена").performClick()
        assertTrue(graph.vault.state.value.confirmActions)
        compose.onNodeWithTag("agent-access-mode").performClick()
        compose.onNodeWithTag("access-mode-full").performClick()
        compose.onNodeWithTag("confirm-full-access").performClick()
        compose.waitUntil(5000) {!graph.vault.state.value.confirmActions}
        compose.onNodeWithText("Полный доступ").assertIsDisplayed()
        val savedVault=SettingsVault(compose.activity)
        assertNull(savedVault.loadError,savedVault.loadError)
        assertFalse(savedVault.state.value.confirmActions)
        screenshot("38-chat-full-access")
        compose.onNodeWithTag("agent-access-mode").performClick()
        compose.onNodeWithTag("access-mode-ask").performClick()
        compose.waitUntil(5000) {graph.vault.state.value.confirmActions}
        compose.onNodeWithText("Настройки",useUnmergedTree=true).performClick()
        compose.onAllNodesWithText("Системный промпт").assertCountEquals(0)
        compose.onNodeWithText("Параметры модели").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("model-instructions"))
        compose.onNodeWithTag("model-instructions").performTextInput("Пиши коротко и дружелюбно")
        compose.onNodeWithText("Сохранить промпт").performScrollTo().performClick()
        compose.waitUntil(5000) {graph.vault.state.value.connection().generation().instruction=="Пиши коротко и дружелюбно"}
        assertEquals("Пиши коротко и дружелюбно",SettingsVault(compose.activity).state.value.connection().generation().instruction)
        screenshot("39-system-prompt")
    }
    @Test fun attachmentButtonLaunchesAndroidDocumentPickerAndSavedFilesRenderInChat() {
        val file=graph.chats.attachments.store("notes.txt","text/plain","Вложенный текст".toByteArray())
        runBlocking {
            graph.vault.update {it.copy(onboarded=true)}
            val id=graph.chats.ensure("Files");graph.chats.append(id,"user","Посмотри файл",listOf(file))
        }
        compose.setContent {AppRoot(graph)}
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onNodeWithTag("chat-attach").assertIsEnabled().performClick()
        val intent=org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,intent.action)
        assertEquals("*/*",intent.type);assertTrue(intent.getBooleanExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE,false))
        screenshot("40-chat-attachments")
    }

    @Test fun themeColorsInterpolateAndCanReverseMidTransition() {
        val theme=androidx.compose.runtime.mutableStateOf(AppSettings(theme="dark",dynamicColors=false))
        var color=androidx.compose.ui.graphics.Color.Transparent
        compose.mainClock.autoAdvance=false
        compose.setContent {LumaTheme(theme.value) {val current=MaterialTheme.colorScheme.background;SideEffect {color=current};Surface(Modifier.fillMaxSize()) {}}}
        compose.mainClock.advanceTimeBy(32);compose.waitForIdle();val dark=color
        compose.runOnUiThread {theme.value=theme.value.copy(theme="light")}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.mainClock.advanceTimeBy(180);compose.waitForIdle();val middle=color
        assertNotEquals(dark,middle)
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle();val light=color
        assertNotEquals(light,middle)
        compose.runOnUiThread {theme.value=theme.value.copy(theme="dark")}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.mainClock.advanceTimeBy(180);compose.waitForIdle();val reversed=color
        assertTrue(reversed.red>dark.red && reversed.red<light.red)
        compose.runOnUiThread {theme.value=theme.value.copy(theme="light")}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.mainClock.advanceTimeBy(16);compose.waitForIdle()
        assertTrue(kotlin.math.abs(color.red-reversed.red)<.08f)
        compose.mainClock.advanceTimeBy(650);compose.waitForIdle();assertEquals(light,color)
        compose.mainClock.autoAdvance=true
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun animatedComposerKeepsTextSelectionPasteAndImeCompositionImmediate() {
        val input=mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("ab"))
        var transform: androidx.compose.ui.text.input.VisualTransformation=androidx.compose.ui.text.input.VisualTransformation.None
        compose.setContent {LumaTheme(AppSettings(reducedMotion=false,dynamicColors=false)) {
            val effect=smoothInput(input.value);SideEffect {transform=effect}
            androidx.compose.foundation.text.BasicTextField(input.value,{input.value=it},Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("animated-input"),visualTransformation=effect)
        }}
        compose.onNodeWithTag("animated-input").performTextInputSelection(androidx.compose.ui.text.TextRange(1))
        compose.onNodeWithTag("animated-input").performTextInput("🌙")
        assertEquals("a🌙b",input.value.text);assertEquals(3,input.value.selection.start)
        compose.onNodeWithTag("animated-input").performTextReplacement("Вставленный текст")
        assertEquals("Вставленный текст",input.value.text)
        val composing=androidx.compose.ui.text.input.TextFieldValue("тест",androidx.compose.ui.text.TextRange(4),androidx.compose.ui.text.TextRange(0,4))
        compose.runOnUiThread {input.value=composing};compose.waitForIdle()
        assertEquals(composing,input.value);assertSame(androidx.compose.ui.text.input.VisualTransformation.None,transform)
    }

    @Test fun markdownReplyUsesFloatingComposerAndOrbFollowsTheAnswerAtLeft() {
        runBlocking {
            graph.vault.update {it.copy(onboarded=true)}
            val id=graph.chats.ensure("Markdown")
            graph.chats.append(id,"user","Покажи пример оформления")
            graph.chats.append(id,"assistant","# Ответ Luma\n\n**Готово.** Вот план:\n\n- Первый шаг\n- Второй шаг\n\n> Небольшая цитата\n\n| Вид | Значение |\n|---|---|\n| Орбиты | 144 |\n\n```kotlin\nval luma = true\n```")
        }
        compose.setContent {AppRoot(graph)}
        compose.waitUntil(5000) {nativeMarkdown().any {it.text.toString().contains("Ответ Luma")}}
        compose.waitForIdle()
        val orb=compose.onNodeWithTag("chat-follow-orb").getUnclippedBoundsInRoot()
        assertTrue((orb.left+orb.right)/2f<70.dp)
        assertEquals(44.dp,orb.right-orb.left)
        contained("chat-input","chat-composer")
        compose.onNodeWithTag("markdown-table").assertExists()
        compose.onNodeWithTag("token-meter").assertExists()
        // Attach, field and send sit on one line: their vertical centres agree within 2 dp.
        val attach=compose.onNodeWithTag("chat-attach").getUnclippedBoundsInRoot()
        val field=compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs(((attach.top+attach.bottom)/2f-(field.top+field.bottom)/2f).value)<2f)
        screenshot("31-chat-markdown")
    }
    @Test fun scrollingEnablesMotionBlurAndRestingFrameBecomesSharpAgain() {
        compose.mainClock.autoAdvance=false
        compose.setContent {LumaTheme(AppSettings(reducedMotion=false,visuals=VisualSettings(motionBlur=true))) {
            MotionLazyColumn(Modifier.fillMaxSize().testTag("blur-list")) {items(100) {androidx.compose.material3.Text("Строка $it",Modifier.fillMaxWidth().height(56.dp))}}
        }}
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("blur-list").performTouchInput {swipeUp(durationMillis=250)}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle();compose.mainClock.advanceTimeBy(100);compose.waitForIdle()
        val moving=compose.onNodeWithTag("blur-list").fetchSemanticsNode().config[MotionBlurAmount]
        assertTrue("Scroll should enable blur: $moving",moving>.05f)
        compose.mainClock.advanceTimeBy(6000);compose.waitForIdle()
        val stopped=compose.onNodeWithTag("blur-list").fetchSemanticsNode().config[MotionBlurAmount]
        assertEquals(0f,stopped,.001f)
        compose.mainClock.autoAdvance=true
    }
    @Test fun singleOrbFliesFromWelcomeToReplyAndRestsAtItsLastLine() {
        runBlocking {graph.vault.update {it.copy(onboarded=true,reducedMotion=false)}}
        compose.mainClock.autoAdvance=false
        compose.setContent {AppRoot(graph)}
        compose.mainClock.advanceTimeBy(2200);compose.waitForIdle()
        val home=compose.onNodeWithTag("chat-follow-orb").getUnclippedBoundsInRoot()
        assertTrue((home.left+home.right)/2f>150.dp)
        compose.runOnUiThread {graph.chatBusy.value=true}
        val id=runBlocking {graph.chats.ensure("Live reply").also {graph.chats.append(it,"user","Расскажи подробнее")}}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle();compose.mainClock.advanceTimeBy(900);compose.waitForIdle()
        val left=compose.onNodeWithTag("chat-follow-orb").getUnclippedBoundsInRoot()
        assertTrue((left.left+left.right)/2f<70.dp)
        assertEquals(44.dp,left.right-left.left)
        runBlocking {graph.chats.append(id,"assistant","# Начало ответа\n\nПервая строка.\n\nПоследняя строка ответа.",reasoning="Сначала подумаю, потом отвечу.",stats=TokenStats(3200,120,4000,10000,6000))}
        compose.runOnUiThread {graph.chatBusy.value=false}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.waitUntil(5000) {nativeMarkdown().any {it.text.toString().contains("Последняя строка ответа")}}
        compose.mainClock.advanceTimeBy(1200);compose.waitForIdle()
        val replyId=graph.chats.messages.value.last().id
        val reply=compose.onNodeWithTag("reply-$replyId").getUnclippedBoundsInRoot()
        val orb=compose.onNodeWithTag("chat-follow-orb").getUnclippedBoundsInRoot()
        val orbCentre=(orb.top+orb.bottom)/2f
        // The answer arrives whole; the orb rests beside its last line instead of chasing a reveal.
        assertTrue("Orb $orbCentre is not at the end of the reply ${reply.top}..${reply.bottom}",orbCentre>(reply.top+reply.bottom)/2f && orbCentre<reply.bottom+24.dp)
        compose.onNodeWithTag("thinking-block").assertExists()
        compose.onNodeWithText("Рассуждения · 2,0 с").assertExists()
        compose.onNodeWithTag("copy-$replyId").assertExists()
        compose.onNodeWithText("Сначала подумаю, потом отвечу.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Показать рассуждения").performClick()
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle()
        compose.onNodeWithText("Сначала подумаю, потом отвечу.").assertExists()
        screenshot("32-reply-whole-with-thinking")
        compose.mainClock.autoAdvance=true
    }

    @Test fun agentCardHoldsTheOrbSlotInsideItsEdges() {
        val run=RunState(active=true,step=1,stage="Обдумываю следующий шаг",startedAt=android.os.SystemClock.elapsedRealtime()-4000)
        compose.setContent {LumaTheme(AppSettings(theme="dark",dynamicColors=false,reducedMotion=true)) {Surface(Modifier.fillMaxSize()) {
            Box(Modifier.padding(16.dp).testTag("run-card")) {RunCard(run,{},{},{},{},maxSteps=0,elapsed=4000,meterActive=true,orbSlot=Modifier.testTag("orb-slot"))}
        }}}
        val card=compose.onNodeWithTag("run-card").getUnclippedBoundsInRoot()
        val slot=compose.onNodeWithTag("orb-slot").getUnclippedBoundsInRoot()
        // The 44 dp orb is drawn over this slot: it must sit inside the card with room for its particles.
        assertEquals(44.dp,slot.right-slot.left)
        assertTrue("Slot $slot leaves the card $card",slot.left>=card.left+12.dp && slot.top>=card.top+12.dp)
        screenshot("41-agent-card-orb-slot")
    }

    @Test fun holdingFingerStillLetsMotionBlurFadeBeforeTheGestureEnds() {
        val state=androidx.compose.foundation.lazy.LazyListState()
        compose.mainClock.autoAdvance=false
        compose.setContent {LumaTheme(AppSettings(reducedMotion=false,visuals=VisualSettings(motionBlur=true))) {
            MotionLazyColumn(Modifier.fillMaxSize().testTag("held-list"),state=state) {items(100) {androidx.compose.material3.Text("Строка $it",Modifier.fillMaxWidth().height(56.dp))}}
        }}
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("held-list").performTouchInput {down(center);moveTo(center-Offset(0f,240f),delayMillis=120)}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.mainClock.advanceTimeBy(48);compose.waitForIdle()
        assertTrue(state.isScrollInProgress)
        assertTrue(compose.onNodeWithTag("held-list").fetchSemanticsNode().config[MotionBlurAmount]>.05f)
        compose.mainClock.advanceTimeBy(700);compose.waitForIdle()
        assertTrue(state.isScrollInProgress)
        assertEquals(0f,compose.onNodeWithTag("held-list").fetchSemanticsNode().config[MotionBlurAmount],.001f)
        compose.onNodeWithTag("held-list").performTouchInput {advanceEventTime(800);up()}
        compose.mainClock.autoAdvance=true
    }

    private fun nativeMarkdown(): List<MarkdownView> {
        val result=mutableListOf<MarkdownView>()
        compose.runOnUiThread {
            fun visit(view: android.view.View) {if(view is MarkdownView) result+=view;if(view is android.view.ViewGroup) for(i in 0 until view.childCount) visit(view.getChildAt(i))}
            visit(compose.activity.window.decorView)
        }
        return result
    }

    private fun screenshot(name:String) {
        compose.waitForIdle()
        lateinit var bitmap: android.graphics.Bitmap
        compose.runOnUiThread {
            val view=compose.activity.window.decorView
            bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val folder=File("build/previews").apply{mkdirs()}
        File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun contained(tag:String,parent:String) {
        val node=compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val bounds=compose.onNodeWithTag(parent).getUnclippedBoundsInRoot()
        assertTrue("$tag overflows $parent: $node outside $bounds",node.top>=bounds.top && node.bottom<=bounds.bottom && node.left>=bounds.left && node.right<=bounds.right)
    }
    private fun containedText(text:String,parent:String) {
        val interaction=compose.onNodeWithText(text).assertIsDisplayed()
        val node=interaction.getUnclippedBoundsInRoot()
        val bounds=compose.onNodeWithTag(parent).getUnclippedBoundsInRoot()
        assertTrue("Text clipped: $text $node outside $bounds",node.top>=bounds.top && node.bottom<=bounds.bottom)
        val layouts=mutableListOf<TextLayoutResult>()
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertFalse("Text overflow: $text ${layouts.map { "${it.size}, height=${it.didOverflowHeight}, width=${it.didOverflowWidth}" }}",layouts.any{it.hasVisualOverflow})
    }
}

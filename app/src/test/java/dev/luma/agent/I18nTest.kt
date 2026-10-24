package dev.luma.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class I18nTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    @After fun restore() {I18n.choose("ru")}
    @Test fun allBundledLanguagesHaveSameKeysAndKeepFormattingArguments() {
        val token=Regex("\\{\\d+\\}|%(?:\\d+\\$)?[\\d.]*[sdf]")
        var keys: Set<String>?=null
        for(code in listOf("en","zh","de","fr")) {
            val data=JSONObject(context.assets.open("i18n/$code.json").bufferedReader().use {it.readText()})
            val actual=data.keys().asSequence().toSet()
            assertTrue(actual.size>1000)
            keys?.let {assertEquals(code,it,actual)};keys=actual
            actual.forEach {key ->assertEquals("$code: $key",token.findAll(key).map {it.value}.sorted().toList(),token.findAll(data.getString(key)).map {it.value}.sorted().toList())}
        }
    }
    @Test fun localeCanChangeWithoutChangingUserTextOrLosingDynamicValues() {
        I18n.initialize(context,"en")
        assertEquals("Settings",tr("Настройки"))
        assertTrue(tr("Контекст: {0} токенов",8192).contains("8192"))
        assertFalse(tr("Контекст: 8192 токенов").contains("Контекст"))
        assertEquals("A user's own text",tr("A user's own text"))
        I18n.choose("zh");assertEquals("模型",tr("Модели"))
        I18n.choose("de");assertEquals("Einstellungen",tr("Настройки"))
        I18n.choose("fr");assertEquals("Paramètres",tr("Настройки"))
        I18n.choose("ru");assertEquals("Настройки",tr("Настройки"))
        I18n.choose("system","zh");assertEquals("zh",I18n.language)
    }
}

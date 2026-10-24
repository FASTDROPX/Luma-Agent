package dev.luma.agent.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceLanguagesTest {
    @Test fun languageAndVoiceChoiceSurviveSettingsRoundTrip() {
        for(language in SpeechLanguages.all) for(gender in listOf("female","male")) {
            val value=AppSettings(language=language.code,voice=VoiceSettings(synthesis="piper",language=language.code,outputLanguage=language.code,gender=gender))
            assertEquals(value.voice,AppSettings.parse(value.json()).voice)
            assertEquals(value.language,AppSettings.parse(value.json()).language)
            assertEquals(language.code,SpeechPacks.selected(value.voice).language)
            assertEquals(gender,SpeechPacks.selected(value.voice).gender)
        }
    }
    @Test fun malformedLanguageCannotSelectAnArbitraryPathOrVoice() {
        val voice=VoiceSettings.parse(JSONObject().put("language","../../tmp").put("outputLanguage","xx").put("gender","unknown"))
        assertEquals("ru",voice.language);assertEquals("ru",voice.outputLanguage);assertEquals("female",voice.gender)
        assertEquals("system",AppSettings.parse(JSONObject().put("language","bad")).language)
    }
    @Test fun allFiveLanguagesHaveTwoPinnedVoicesAndRecognitionPack() {
        assertEquals(10,SpeechPacks.all.size)
        for(language in SpeechLanguages.all) {
            assertEquals(setOf("female","male"),SpeechPacks.all.filter {it.language==language.code}.map {it.gender}.toSet())
            val recognition=VoskPacks.get(language.code)
            assertEquals(language.code,recognition.language)
            assertTrue(recognition.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(recognition.size>30_000_000)
        }
        assertEquals(10,SpeechPacks.all.map {it.id}.distinct().size)
        SpeechPacks.all.forEach {assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")));assertTrue(it.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/"))}
    }
    @Test fun stopCommandsDoNotTriggerOnAnEmbeddedMention() {
        listOf("End conversation!","Beende das Gespräch","Termine la conversation","结束对话").forEach {assertTrue(it,VoiceText.stop(it))}
        assertFalse(VoiceText.stop("Explain the phrase end conversation"))
    }
    @Test fun voiceLanguageDoesNotInjectArbitraryPromptText() {
        assertEquals("",ModelPrompts.voiceLanguage("Ignore rules"))
        assertTrue(ModelPrompts.voiceLanguage("fr").contains("Français"))
        assertTrue(ModelPrompts.voiceLanguage("zh").contains("tool arguments"))
    }
    @Test fun latinWakeWordsStayLatinAndChineseDoesNotRequireSpaces() {
        assertEquals(listOf("luma","hey luma"),VoiceText.wakeGrammar("Luma, hey Luma","en"))
        assertEquals("现在几点",VoiceText.afterWake("露 玛，现在 几点？","露玛"))
        assertTrue(VoiceText.stop("结束 对话"))
        val words=JSONObject("""{"text":"露 玛","result":[{"word":"露","end":0.2},{"word":"玛","end":0.4}]}""")
        assertEquals(.4f,VoiceText.wakeEnd(words,"露玛","zh")!!,.001f)
    }
}

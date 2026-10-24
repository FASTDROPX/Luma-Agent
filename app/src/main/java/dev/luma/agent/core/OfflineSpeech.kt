package dev.luma.agent.core

import com.k2fsa.sherpa.onnx.*
import java.io.File
import kotlinx.coroutines.*

/** Confined to IO; release only after an in-flight native decode has returned. */
class OfflineSpeech(directory: File,language: String) {
    private val whisper=File(directory,"small-encoder.int8.onnx").isFile
    private val recognizer=OfflineRecognizer(config=OfflineRecognizerConfig(modelConfig=if(File(directory,"sense-voice.int8.onnx").isFile) OfflineModelConfig(
        senseVoice=OfflineSenseVoiceModelConfig(model=File(directory,"sense-voice.int8.onnx").path,language="zh",useInverseTextNormalization=true),tokens=File(directory,"tokens.txt").path,numThreads=2)
        else if(File(directory,"model.int8.onnx").isFile) OfflineModelConfig(
        nemo=OfflineNemoEncDecCtcModelConfig(model=File(directory,"model.int8.onnx").path),tokens=File(directory,"tokens.txt").path,numThreads=2)
        else OfflineModelConfig(
        whisper=OfflineWhisperModelConfig(encoder=File(directory,"small-encoder.int8.onnx").path,
            decoder=File(directory,"small-decoder.int8.onnx").path,language=language,task="transcribe"),
        tokens=File(directory,"small-tokens.txt").path,numThreads=2,modelType="whisper")))
    val vad=Vad(config=VadModelConfig(sileroVadModelConfig=SileroVadModelConfig(
        model=File(directory,"silero_vad.onnx").path,minSilenceDuration=.85f,minSpeechDuration=.25f,maxSpeechDuration=60f)))
    suspend fun transcribe(samples: FloatArray): String {
        currentCoroutineContext().ensureActive()
        if(samples.size<3200) return ""
        if(whisper && samples.size>25*16000) {
            var result="";var start=0
            while(start<samples.size) {
                currentCoroutineContext().ensureActive()
                val end=minOf(start+25*16000,samples.size)
                result=VoiceText.joinTranscripts(result,decode(samples.copyOfRange(start,end)))
                if(end==samples.size) break
                start=end-16000
            }
            return result
        }
        return decode(samples)
    }
    private suspend fun decode(samples: FloatArray): String {
        val stream=recognizer.createStream()
        try {
            stream.acceptWaveform(samples,16000)
            recognizer.decode(stream)
            currentCoroutineContext().ensureActive()
            return recognizer.getResult(stream).text.trim()
        } finally {stream.release()}
    }
    fun release() {vad.release();recognizer.release()}
}

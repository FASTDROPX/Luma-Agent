package dev.luma.agent.core

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SpeechEnvelopeTest {
    @Test fun decodedAudioDrivesLevelAndSilenceReturnsToZero() {
        val envelope=SpeechEnvelope(1000,1,AudioFormat.ENCODING_PCM_16BIT)
        val pcm=ByteBuffer.allocate(80*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(40) {pcm.putShort(3277)};repeat(40) {pcm.putShort(0)}
        envelope.append(pcm.array())
        assertEquals(.5f,envelope.at(0),.01f)
        assertEquals(0f,envelope.at(40),.001f)
        assertEquals(0f,envelope.at(80),.001f)
    }
    @Test fun floatAndStereoOutputUseTheCorrectTimeBase() {
        val envelope=SpeechEnvelope(1000,2,AudioFormat.ENCODING_PCM_FLOAT)
        val pcm=ByteBuffer.allocate(80*4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(80) {pcm.putFloat(.1f)};envelope.append(pcm.array())
        assertEquals(.5f,envelope.at(39),.001f)
        assertEquals(0f,envelope.at(40),.001f)
    }
}

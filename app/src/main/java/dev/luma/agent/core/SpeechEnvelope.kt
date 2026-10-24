package dev.luma.agent.core

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** A 40-ms RMS envelope of decoded speech. It contains no transcript or persisted microphone audio. */
internal class SpeechEnvelope(private val rate: Int,private val channels: Int,private val encoding: Int) {
    private val bucket=maxOf(1,rate*channels/25)
    private val levels=ArrayList<Float>()
    private var count=0;private var energy=0.0
    @Synchronized fun append(bytes: ByteArray) {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val width=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else if(encoding==AudioFormat.ENCODING_PCM_8BIT) 1 else 2
        while(b.remaining()>=width) {
            val sample=when(encoding) {
                AudioFormat.ENCODING_PCM_FLOAT->b.float.toDouble()
                AudioFormat.ENCODING_PCM_8BIT->((b.get().toInt() and 255)-128)/128.0
                else->b.short/32768.0
            }
            energy+=sample*sample;count++
            if(count==bucket) {levels.add((sqrt(energy/count)*5).toFloat().coerceIn(0f,1f));count=0;energy=0.0}
        }
    }
    @Synchronized fun at(milliseconds: Long): Float {
        val index=(milliseconds/40).toInt()
        return levels.getOrNull(index) ?: if(index==levels.size && count>0) (sqrt(energy/count)*5).toFloat().coerceIn(0f,1f) else 0f
    }
    companion object {
        /** Decode cloud speech once, then follow MediaPlayer's actual playback position. */
        suspend fun decode(file: File): SpeechEnvelope? {
            val extractor=MediaExtractor();var codec: MediaCodec?=null
            try {
                extractor.setDataSource(file.path)
                val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")} ?: return null
                extractor.selectTrack(track)
                val format=extractor.getTrackFormat(track)
                codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format,null,null,0);codec.start()
                var envelope: SpeechEnvelope?=null
                var inputEnded=false;var ended=false
                val info=MediaCodec.BufferInfo()
                while(!ended) {
                    currentCoroutineContext().ensureActive()
                    if(!inputEnded) {
                        val index=codec.dequeueInputBuffer(10000)
                        if(index>=0) {
                            val buffer=codec.getInputBuffer(index)!!;val size=extractor.readSampleData(buffer,0)
                            if(size<0) {codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                            else {codec.queueInputBuffer(index,0,size,extractor.sampleTime,0);extractor.advance()}
                        }
                    }
                    val index=codec.dequeueOutputBuffer(info,10000)
                    if(index>=0) {
                        if(envelope==null) {
                            val out=codec.outputFormat
                            envelope=SpeechEnvelope(out.getInteger(MediaFormat.KEY_SAMPLE_RATE),out.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                if(out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT)
                        }
                        if(info.size>0) {
                            val b=codec.getOutputBuffer(index)!!;b.position(info.offset);b.limit(info.offset+info.size)
                            val bytes=ByteArray(info.size);b.get(bytes);envelope.append(bytes)
                        }
                        ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;codec.releaseOutputBuffer(index,false)
                    }
                }
                return envelope
            } finally {runCatching {codec?.stop()};codec?.release();extractor.release()}
        }
    }
}

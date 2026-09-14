package io.github.pgodlews.wormhole

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class AudioRenderer(private val telemetry: StreamTelemetry? = null) {
    private val stateLock = Any()
    private var active = false
    private var closed = false
    private var session = 0L
    private var currentVolumeDb = 0.0f
    private val frameQueue = ArrayDeque<AudioPacket>()

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Wormhole audio") }

    // Confined to worker thread
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var pcmBuffer = ByteArray(4096)

    private val pump = worker.scheduleWithFixedDelay({ tick() }, 0, 5, TimeUnit.MILLISECONDS)

    data class AudioPacket(val data: ByteArray, val ntpTimestamp: Long, val ct: Int)

    private var loggedFrames = 0
    private var fedFrames = 0L

    var audioEnabled: Boolean = true
        set(value) = synchronized(stateLock) {
            if (field != value) {
                field = value
                telemetry?.onAudioStateChanged(enabled = value, active = active)
                if (active && !closed) {
                    if (value) {
                        worker.execute { setupSession() }
                    } else {
                        frameQueue.clear()
                        worker.execute { teardownSession() }
                    }
                }
            }
        }

    fun beginSession() = synchronized(stateLock) {
        if (!closed) {
            session++
            active = true
            loggedFrames = 0
            fedFrames = 0L
            frameQueue.clear()
            telemetry?.onAudioStateChanged(enabled = audioEnabled, active = true)
            telemetry?.onAudioFormatConfigured("AAC-ELD", SAMPLE_RATE, 2)
            if (audioEnabled) {
                worker.execute { setupSession() }
            }
        }
    }

    fun endSession() = synchronized(stateLock) {
        session++
        active = false
        loggedFrames = 0
        frameQueue.clear()
        telemetry?.onAudioStateChanged(enabled = audioEnabled, active = false)
        worker.execute { teardownSession() }
    }

    fun onFrame(data: ByteArray, ntpTimestamp: Long, ct: Int) = synchronized(stateLock) {
        if (active && !closed && audioEnabled && data.isNotEmpty()) {
            telemetry?.onAudioFrameReceived(data.size)
            if (loggedFrames < 5) {
                Log.i(TAG, "Audio frame #$loggedFrames: size=${data.size}, ct=$ct, ts=$ntpTimestamp")
                loggedFrames++
            }
            // Keep at most 30 frames (~320ms) to bound latency and memory
            if (frameQueue.size >= MAX_QUEUE_SIZE) {
                frameQueue.pollFirst()
            }
            frameQueue.addLast(AudioPacket(data, ntpTimestamp, ct))
        }
    }

    fun setVolume(volumeDb: Float) = synchronized(stateLock) {
        currentVolumeDb = volumeDb
        val linear = dbToLinear(volumeDb)
        telemetry?.onAudioVolumeChanged(volumeDb)
        worker.execute {
            audioTrack?.setVolume(linear)
        }
    }

    fun flush() = synchronized(stateLock) {
        frameQueue.clear()
        worker.execute {
            runCatching { decoder?.flush() }
            runCatching { audioTrack?.flush() }
        }
    }

    fun close() = synchronized(stateLock) {
        if (!closed) {
            closed = true
            active = false
            frameQueue.clear()
            pump.cancel(false)
            worker.execute {
                teardownSession()
                releaseAudioTrack()
            }
            worker.shutdown()
        }
    }

    private fun setupSession() {
        if (audioTrack == null) {
            initAudioTrack()
        }
        val linear = dbToLinear(synchronized(stateLock) { currentVolumeDb })
        audioTrack?.setVolume(linear)
        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { audioTrack?.play() }
        }
        if (decoder == null) {
            decoder = createAacDecoder()
        }
    }

    private fun teardownSession() {
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
        }
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize * 4, 16384)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            Log.i(TAG, "AudioTrack initialized (buffer size: $bufferSize)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    private fun releaseAudioTrack() {
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }

    private fun createAacDecoder(): MediaCodec? {
        return try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setByteBuffer("csd-0", ByteBuffer.wrap(CSD_AAC_ELD_44100_STEREO_480))
            }
            codec.configure(format, null, null, 0)
            codec.start()
            Log.i(TAG, "AAC-ELD audio decoder started: ${codec.name}")
            codec
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AAC-ELD audio decoder: ${e.message}", e)
            null
        }
    }

    private fun tick() {
        val (isActive, packet) = synchronized(stateLock) {
            Pair(active && !closed, frameQueue.pollFirst())
        }
        if (!isActive) return

        val currentDecoder = decoder ?: return
        val currentTrack = audioTrack ?: return

        // 1. Submit input frame if available
        if (packet != null) {
            try {
                val inputIndex = currentDecoder.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val inputBuffer = currentDecoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(packet.data)
                        currentDecoder.queueInputBuffer(
                            inputIndex,
                            0,
                            packet.data.size,
                            packet.ntpTimestamp / 1000,
                            0
                        )
                    }
                } else {
                    // Put back if decoder input full
                    synchronized(stateLock) {
                        if (active && !closed) frameQueue.addFirst(packet)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error queueing audio frame: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 2. Drain decoded PCM output and write to AudioTrack
        try {
            var drained = 0
            while (drained < 8) {
                val outputIndex = currentDecoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = currentDecoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (pcmBuffer.size < bufferInfo.size) {
                                pcmBuffer = ByteArray(bufferInfo.size)
                            }
                            outputBuffer.get(pcmBuffer, 0, bufferInfo.size)
                            currentTrack.write(pcmBuffer, 0, bufferInfo.size)
                            telemetry?.onAudioFramePlayed()
                        }
                        currentDecoder.releaseOutputBuffer(outputIndex, false)
                        drained++
                        fedFrames++
                        if (fedFrames % 500 == 0L) {
                            Log.i(TAG, "Audio PCM frames played: $fedFrames")
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "Audio decoder format changed: ${currentDecoder.outputFormat}")
                        val rate = currentDecoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = currentDecoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        telemetry?.onAudioFormatConfigured("AAC-ELD", rate, channels)
                        break
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error draining audio output: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    internal companion object {
        const val TAG = "WormholeAudio"
        const val SAMPLE_RATE = 44100
        const val MAX_QUEUE_SIZE = 30

        /** AudioSpecificConfig for AAC-ELD: 44.1 kHz, 2 channels, 480 samples/frame */
        val CSD_AAC_ELD_44100_STEREO_480 = byteArrayOf(
            0xF8.toByte(), // 11111 (escape 31) + 000 (part of 39-32=7)
            0xE8.toByte(), // 111 (rest of 7) + 0100 (44.1kHz index 4) + 0 (part of 2ch)
            0x50.toByte(), // 010 (rest of 2ch) + 1 (480 spf) + 0000 (resilience flags)
            0x00.toByte()  // 0000 (ELDEXT_TERM) + 00 (epConfig) + 00 (padding)
        )

        /** AudioSpecificConfig for AAC-ELD: 44.1 kHz, 2 channels, 512 samples/frame */
        val CSD_AAC_ELD_44100_STEREO_512 = byteArrayOf(
            0xF8.toByte(),
            0xE8.toByte(),
            0x20.toByte(), // 010 (rest of 2ch) + 0 (512 spf) + 0000 (resilience flags)
            0x00.toByte()  // 0000 (ELDEXT_TERM) + 00 (epConfig) + 00 (padding)
        )

        fun dbToLinear(volumeDb: Float): Float {
            return if (volumeDb <= -144.0f) 0.0f
            else 10.0f.pow(volumeDb / 20.0f).coerceIn(0.0f, 1.0f)
        }
    }
}

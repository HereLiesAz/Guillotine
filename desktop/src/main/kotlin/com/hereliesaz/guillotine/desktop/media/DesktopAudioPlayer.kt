package com.hereliesaz.guillotine.desktop.media

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class DesktopAudioPlayer {

    @Volatile var gain: Float = 1f
    @Volatile var pan: Float = 0f

    private var playThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var seekToMs: Long? = null
    @Volatile private var playPositionMs: Long = 0L

    fun start(file: File, sourceTimeMs: Long, rate: Float) {
        stop()
        running = true
        playPositionMs = sourceTimeMs
        playThread = thread(isDaemon = true, name = "audio-player") {
            var g: FFmpegFrameGrabber? = null
            var line: SourceDataLine? = null
            try {
                g = FFmpegFrameGrabber(file)
                g.sampleRate = 44100
                g.audioChannels = 2
                g.sampleFormat = avutil.AV_SAMPLE_FMT_S16
                g.start()

                g.timestamp = sourceTimeMs * 1000L

                val format = AudioFormat(44100f, 16, 2, true, false)
                line = AudioSystem.getSourceDataLine(format)
                line.open(format, 44100 * 2 * 2 / 5)
                line.start()

                while (running) {
                    val pendingSeek = seekToMs
                    if (pendingSeek != null) {
                        g.timestamp = pendingSeek * 1000L
                        seekToMs = null
                        line.flush()
                    }

                    val frame = g.grabSamples() ?: break
                    val samples = frame.samples ?: continue
                    if (samples.isEmpty()) continue

                    playPositionMs = frame.timestamp / 1000L

                    val buf = samples[0] as? ShortBuffer ?: continue
                    buf.rewind()
                    val count = buf.remaining()
                    val bytes = ByteArray(count * 2)

                    val currentGain = gain
                    val currentPan = pan
                    val leftGain = currentGain * if (currentPan > 0f) 1f - currentPan else 1f
                    val rightGain = currentGain * if (currentPan < 0f) 1f + currentPan else 1f

                    var bi = 0
                    while (buf.hasRemaining()) {
                        val leftSample = buf.get()
                        val rightSample = if (buf.hasRemaining()) buf.get() else leftSample

                        val l16 = (leftSample * leftGain).roundToInt().coerceIn(-32768, 32767).toShort()
                        val r16 = (rightSample * rightGain).roundToInt().coerceIn(-32768, 32767).toShort()

                        bytes[bi++] = (l16.toInt() and 0xFF).toByte()
                        bytes[bi++] = (l16.toInt() shr 8 and 0xFF).toByte()
                        bytes[bi++] = (r16.toInt() and 0xFF).toByte()
                        bytes[bi++] = (r16.toInt() shr 8 and 0xFF).toByte()
                    }

                    if (bi > 0) line.write(bytes, 0, bi)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { g?.stop(); g?.release() }
                runCatching { line?.drain(); line?.stop(); line?.close() }
            }
        }
    }

    fun seek(sourceTimeMs: Long) {
        seekToMs = sourceTimeMs
    }

    fun stop() {
        running = false
        playThread?.let {
            it.interrupt()
            runCatching { it.join(1000) }
        }
        playThread = null
    }

    fun release() {
        stop()
    }

    val positionMs: Long
        get() = playPositionMs
}

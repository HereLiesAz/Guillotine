package com.hereliesaz.guillotine.desktop.media

import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Audio playback via FFmpegFrameGrabber → javax.sound.sampled.SourceDataLine.
 * Gain and pan are volatile fields updated from the UI thread.
 */
class DesktopAudioPlayer {

    @Volatile var gain: Float = 1f
    @Volatile var pan: Float = 0f

    private var playThread: Thread? = null
    private var grabber: FFmpegFrameGrabber? = null
    private var line: SourceDataLine? = null
    @Volatile private var running = false

    fun start(file: File, sourceTimeMs: Long, rate: Float) {
        stop()
        running = true
        playThread = thread(isDaemon = true, name = "audio-player") {
            try {
                val g = FFmpegFrameGrabber(file)
                g.sampleRate = 44100
                g.audioChannels = 2
                g.start()
                grabber = g

                g.timestamp = sourceTimeMs * 1000L

                val format = AudioFormat(44100f, 16, 2, true, false)
                val l = AudioSystem.getSourceDataLine(format)
                l.open(format, 44100 * 2 * 2 / 5) // ~200ms buffer
                l.start()
                line = l

                while (running) {
                    val frame = g.grabSamples() ?: break
                    val samples = frame.samples ?: continue
                    if (samples.isEmpty()) continue

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

                        // Little-endian 16-bit
                        bytes[bi++] = (l16.toInt() and 0xFF).toByte()
                        bytes[bi++] = (l16.toInt() shr 8 and 0xFF).toByte()
                        bytes[bi++] = (r16.toInt() and 0xFF).toByte()
                        bytes[bi++] = (r16.toInt() shr 8 and 0xFF).toByte()
                    }

                    if (bi > 0) l.write(bytes, 0, bi)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { line?.drain(); line?.stop(); line?.close() }
                runCatching { grabber?.stop(); grabber?.release() }
                line = null
                grabber = null
            }
        }
    }

    fun seek(sourceTimeMs: Long) {
        grabber?.timestamp = sourceTimeMs * 1000L
    }

    fun stop() {
        running = false
        playThread?.interrupt()
        playThread = null
    }

    fun release() {
        stop()
    }

    val positionMs: Long
        get() {
            val l = line ?: return 0L
            return l.microsecondPosition / 1000L
        }
}

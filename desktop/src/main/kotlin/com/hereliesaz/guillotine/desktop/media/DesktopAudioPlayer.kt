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

/**
 * Preview audio for one track: decodes the clip's source to S16 stereo and writes it to a Java Sound
 * line. [rate] is the clip's current playback speed (its [com.hereliesaz.guillotine.model.ClipFilters.speed]
 * / SPEED curve) and is applied as a varispeed resample (linear interpolation, pitch follows speed) —
 * the same model the exporter uses — so a sped-up or slowed clip's preview audio stays with its picture.
 */
class DesktopAudioPlayer {

    @Volatile var gain: Float = 1f
    @Volatile var pan: Float = 0f
    /** Playback speed, 0.1..10 (1 = normal). May change while playing. */
    @Volatile var rate: Float = 1f

    private var playThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var seekToMs: Long? = null
    @Volatile private var playPositionMs: Long = 0L

    fun start(file: File, sourceTimeMs: Long, rate: Float) {
        stop()
        running = true
        playPositionMs = sourceTimeMs
        this.rate = rate
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

                // Varispeed state carried across decoded frames: fractional read position relative to
                // the current frame (-1 = the carried last sample of the previous frame).
                var phase = 0.0
                var prevL = 0f
                var prevR = 0f
                while (running) {
                    val pendingSeek = seekToMs
                    if (pendingSeek != null) {
                        g.timestamp = pendingSeek * 1000L
                        seekToMs = null
                        line.flush()
                        phase = 0.0; prevL = 0f; prevR = 0f
                    }

                    val frame = g.grabSamples() ?: break
                    val samples = frame.samples ?: continue
                    if (samples.isEmpty()) continue

                    playPositionMs = frame.timestamp / 1000L

                    val buf = samples[0] as? ShortBuffer ?: continue
                    buf.rewind()
                    val n = buf.remaining() / 2
                    if (n == 0) continue
                    val inL = FloatArray(n)
                    val inR = FloatArray(n)
                    for (i in 0 until n) {
                        inL[i] = buf.get().toFloat()
                        inR[i] = buf.get().toFloat()
                    }

                    val currentGain = gain
                    val currentPan = pan
                    val step = rate.coerceIn(0.1f, 10f).toDouble()
                    val leftGain = currentGain * if (currentPan > 0f) 1f - currentPan else 1f
                    val rightGain = currentGain * if (currentPan < 0f) 1f + currentPan else 1f

                    val outCount = ((n - 1 - phase) / step).toInt().coerceAtLeast(0) + 1
                    val bytes = ByteArray(outCount * 4)
                    var bi = 0
                    while (phase < n - 1 && bi + 4 <= bytes.size) {
                        val i = kotlin.math.floor(phase).toInt()
                        val frac = (phase - i).toFloat()
                        val aL = if (i < 0) prevL else inL[i]
                        val aR = if (i < 0) prevR else inR[i]
                        val leftSample = aL + (inL[i + 1] - aL) * frac
                        val rightSample = aR + (inR[i + 1] - aR) * frac

                        val l16 = (leftSample * leftGain).roundToInt().coerceIn(-32768, 32767).toShort()
                        val r16 = (rightSample * rightGain).roundToInt().coerceIn(-32768, 32767).toShort()

                        bytes[bi++] = (l16.toInt() and 0xFF).toByte()
                        bytes[bi++] = (l16.toInt() shr 8 and 0xFF).toByte()
                        bytes[bi++] = (r16.toInt() and 0xFF).toByte()
                        bytes[bi++] = (r16.toInt() shr 8 and 0xFF).toByte()
                        phase += step
                    }
                    phase -= n
                    prevL = inL[n - 1]
                    prevR = inR[n - 1]

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

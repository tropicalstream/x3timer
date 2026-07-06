package com.tropicalstream.x3timer.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * All audio is synthesized in-process (no asset files): short enveloped tones for
 * cues, and a continuous white/brown-noise soundscape for focus masking.
 *
 * Cues run on a single-thread executor (serialized, cheap). The soundscape runs on
 * its OWN thread so its blocking write-loop never starves the cues. Everything is
 * runCatching-wrapped: audio must never crash the app.
 */
class SoundEngine {

    enum class Noise { OFF, WHITE, BROWN }

    private val sr = 44100
    @Volatile var enabled = true
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "x3timer-audio").apply { isDaemon = true }
    }

    // ---------- one-shot tones ----------

    private fun tone(
        freq: Double, ms: Int, vol: Float = 0.5f,
        square: Boolean = false, attack: Float = 0.01f, release: Float = 0.18f
    ): ShortArray {
        val n = sr * ms / 1000
        val out = ShortArray(n)
        val atk = (n * attack).toInt().coerceAtLeast(1)
        val rel = (n * release).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var s = sin(2 * PI * freq * t)
            if (square) s = if (s >= 0) 1.0 else -1.0
            val env = when {
                i < atk -> i.toFloat() / atk
                i > n - rel -> (n - i).toFloat() / rel
                else -> 1f
            }
            out[i] = (s * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size); o += p.size
        }
        return out
    }

    private fun play(samples: ShortArray) {
        if (!enabled) return
        exec.execute {
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(samples.size * 1000L / sr + 60)
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    // ---------- named cues ----------

    fun countBeep(sec: Int) = play(tone(640.0 + (3 - sec) * 130, 90, 0.5f, square = true))
    fun go() = play(tone(920.0, 300, 0.62f, square = true))
    fun intervalBeep() = play(tone(760.0, 150, 0.55f, square = true))
    fun restBeep() = play(tone(420.0, 200, 0.5f))
    fun roundDone() = play(concat(tone(660.0, 90, 0.5f), tone(990.0, 150, 0.5f)))
    fun workoutDone() = play(
        concat(
            tone(523.0, 120, 0.5f), tone(659.0, 120, 0.5f),
            tone(784.0, 120, 0.5f), tone(1047.0, 300, 0.55f)
        )
    )
    fun focusChime() = play(concat(tone(587.0, 150, 0.4f), tone(880.0, 240, 0.4f)))
    fun breakChime() = play(concat(tone(880.0, 150, 0.4f), tone(587.0, 280, 0.42f)))

    // ---------- continuous soundscape ----------

    @Volatile private var noiseOn = false

    fun startNoise(type: Noise) {
        if (!enabled || type == Noise.OFF || noiseOn) return
        noiseOn = true
        Thread {
            runCatching {
                val bufN = sr / 5
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufN * 2 * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.setVolume(0.16f)
                track.play()
                var last = 0.0
                val buf = ShortArray(bufN)
                while (noiseOn) {
                    for (i in 0 until bufN) {
                        val w = Random.nextDouble(-1.0, 1.0)
                        val v = if (type == Noise.BROWN) {
                            last = (last + 0.02 * w).coerceIn(-1.0, 1.0); last * 3.2
                        } else w
                        buf[i] = (v.coerceIn(-1.0, 1.0) * 0.5 * Short.MAX_VALUE).toInt().toShort()
                    }
                    track.write(buf, 0, bufN)
                }
                runCatching { track.stop() }
                track.release()
            }
        }.apply { isDaemon = true }.start()
    }

    fun stopNoise() {
        noiseOn = false
    }

    fun release() {
        stopNoise()
        exec.shutdownNow()
    }
}

package com.oblivion.djayclone

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure (no Android) rules behind the mix recorder, JVM-tested. */

object CaptureSpec {
    /** 44.1 and 48 kHz are kept; anything else (or unknown) records at 48 kHz. */
    fun chooseRate(deviceRate: Int?): Int = when (deviceRate) {
        44100 -> 44100
        48000 -> 48000
        else -> 48000
    }
}

object CaptureClock {
    /** Time of a recording is the audio actually written, not the wall clock,
     * so a stall can never make the timer run ahead of the file. */
    fun elapsedMs(frames: Long, rate: Int): Long = if (rate <= 0) 0L else frames * 1000L / rate
}

object StorageBudget {
    const val RESERVE_BYTES = 50L * 1024 * 1024
    const val WARN_MS = 15L * 60_000
    const val CRITICAL_MS = 3L * 60_000
    const val DISPLAY_CAP_MS = 99L * 3_600_000

    enum class Level { OK, WARN, CRITICAL, STOP }

    fun bytesPerSecond(rate: Int, channels: Int): Long = rate.toLong() * channels * 2

    /** How much more can be recorded. [saveMultiplier] is 2 while Save copies
     * the file (free space is used once by the take and once by the copy). */
    fun remainingMs(freeBytes: Long, reserveBytes: Long, bytesPerSec: Long, saveMultiplier: Int = 1): Long {
        if (bytesPerSec <= 0 || freeBytes <= reserveBytes) return 0L
        val usable = freeBytes - reserveBytes
        val seconds = usable / (bytesPerSec * saveMultiplier.coerceAtLeast(1))
        return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
    }

    fun shouldStop(freeBytes: Long, reserveBytes: Long = RESERVE_BYTES) = freeBytes <= reserveBytes

    fun level(remainingMs: Long, stop: Boolean = false): Level = when {
        stop -> Level.STOP
        remainingMs < CRITICAL_MS -> Level.CRITICAL
        remainingMs < WARN_MS -> Level.WARN
        else -> Level.OK
    }

    /** "3h 12m", "45m", "<1m"; capped at 99 h. */
    fun format(remainingMs: Long): String {
        val ms = remainingMs.coerceIn(0L, DISPLAY_CAP_MS)
        val totalMin = ms / 60_000
        return when {
            totalMin < 1 -> "<1m"
            totalMin < 60 -> "${totalMin}m"
            else -> "${totalMin / 60}h ${totalMin % 60}m"
        }
    }

    /** Free space needed to Save [fileBytes] into the library (copy + margin). */
    fun saveNeeds(fileBytes: Long): Long = fileBytes + RESERVE_BYTES
}

/** First tap of STOP on a long take only arms; a second within the window stops. */
object StopGuard {
    const val LONG_TAKE_MS = 30_000L
    const val WINDOW_MS = 3_000L

    sealed class Result {
        object Confirmed : Result()
        data class Armed(val armedAt: Long) : Result()
    }

    fun onTap(now: Long, elapsedMs: Long, armedAt: Long?): Result {
        if (elapsedMs < LONG_TAKE_MS) return Result.Confirmed
        if (armedAt != null && now - armedAt < WINDOW_MS) return Result.Confirmed
        return Result.Armed(now)
    }

    fun stillArmed(now: Long, armedAt: Long?): Boolean = armedAt != null && now - armedAt < WINDOW_MS
}

object DiscardPolicy {
    const val CONFIRM_FROM_MS = 10_000L
    fun needsConfirm(elapsedMs: Long, alreadySaved: Boolean): Boolean = !alreadySaved && elapsedMs >= CONFIRM_FROM_MS
}

/** Detects a capture that stopped delivering audio while the timer ran on. */
object CaptureWatchdog {
    const val STALL_MS = 2_000L
    fun isStalled(now: Long, startedAt: Long, lastFrameAt: Long?): Boolean = now - (lastFrameAt ?: startedAt) >= STALL_MS
}

object NotificationText {
    fun title(recording: Boolean) = if (recording) "DJayClone – recording" else "DJayClone"

    fun compose(recording: Boolean, elapsedMs: Long, level: StorageBudget.Level): String {
        if (!recording) return "Preparing to record"
        val base = "Recording your mix · ${TransportMath.formatClock(elapsedMs)}"
        return when (level) {
            StorageBudget.Level.CRITICAL, StorageBudget.Level.STOP -> "$base · Low storage"
            else -> base
        }
    }
}

/** Canonical 44-byte PCM WAV header: unsigned sizes, 4 GiB clamp, repair. */
object WavHeader {
    const val SIZE = 44
    private const val MAX_RIFF = 0xFFFFFFFFL

    data class Info(val sampleRate: Int, val channels: Int, val bits: Int, val dataBytes: Long)

    /** Largest data size a RIFF/WAV can carry for this frame size. */
    fun maxDataBytes(blockAlign: Int): Long = ((MAX_RIFF - 36) / blockAlign) * blockAlign

    fun build(sampleRate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray {
        val blockAlign = channels * bits / 8
        val data = dataBytes.coerceIn(0L, maxDataBytes(blockAlign))
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + data).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(sampleRate); b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort()); b.putShort(bits.toShort())
        b.put("data".toByteArray()); b.putInt(data.toInt())
        return b.array()
    }

    fun isAtLimit(dataBytes: Long, blockAlign: Int): Boolean = dataBytes >= maxDataBytes(blockAlign)

    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < SIZE) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || tag(12) != "fmt " || tag(36) != "data") return null
        if (b.getShort(20).toInt() != 1) return null
        val channels = b.getShort(22).toInt()
        val rate = b.getInt(24)
        val bits = b.getShort(34).toInt()
        if (channels <= 0 || rate <= 0 || bits <= 0 || bits % 8 != 0) return null
        return Info(rate, channels, bits, b.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    /** The header a possibly-truncated file should carry: data = whatever audio
     * is really there, trimmed to whole frames. Null if the header is unusable. */
    fun repaired(headerBytes: ByteArray, fileLength: Long): ByteArray? {
        val info = parse(headerBytes) ?: return null
        val blockAlign = info.channels * info.bits / 8
        val actual = (fileLength - SIZE).coerceAtLeast(0L)
        val data = (actual / blockAlign) * blockAlign
        return build(info.sampleRate, info.channels, info.bits, data)
    }

    fun durationMs(info: Info, actualDataBytes: Long): Long {
        val bps = info.sampleRate.toLong() * info.channels * info.bits / 8
        return if (bps <= 0) 0L else actualDataBytes * 1000L / bps
    }
}

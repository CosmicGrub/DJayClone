package com.oblivion.djayclone

/**
 * What a DJ sets up on a track and expects to find again next time: the cue
 * point, the hot-cue pads, and the stored loop. Pure Kotlin (no Android) so
 * the codec and the validation rules are unit-tested on the JVM.
 *
 * [durationMs] is stored with the record as an identity check: MediaStore ids
 * can be reused after a rescan and a restored backup can re-point an id at a
 * different file, and attaching one song's hot cues to another is far worse
 * than losing them. A record whose duration no longer matches the loaded
 * track (beyond [DURATION_TOLERANCE_MS]) is ignored.
 */
data class SavedTrackState(
    val durationMs: Long,
    val cuePointMs: Long?,
    val loopInMs: Long?,
    val loopOutMs: Long?,
    val loopBeats: Float?,
    val hotCues: List<Long?>,
    /** A BPM the DJ typed/tapped/halved, overriding the analyser for this track. */
    val userBpm: Float? = null,
) {
    /** True when there is nothing worth keeping - the store deletes such a
     * record rather than keeping an empty husk per track ever loaded. */
    val isEmpty: Boolean
        get() = cuePointMs == null && loopInMs == null && loopOutMs == null && userBpm == null && hotCues.all { it == null }
}

object TrackStateCodec {

    /** How far a saved duration may differ from the loaded track's before
     * the record is treated as belonging to a different file. Re-encodes and
     * VBR duration estimates differ by far less than this; a different song
     * almost never lands this close. */
    const val DURATION_TOLERANCE_MS = 2_000L

    private const val VERSION = "v1"

    /** `v1;d=200000;c=1234;l=10000,14000,4.0;h=1000,,3000`
     * Absent values are omitted (c/l) or empty (h slots). Text, not binary:
     * trivially inspectable in a prefs dump, and tolerant of future fields. */
    fun encode(s: SavedTrackState): String = buildString {
        append(VERSION)
        append(";d=").append(s.durationMs)
        s.cuePointMs?.let { append(";c=").append(it) }
        if (s.loopInMs != null && s.loopOutMs != null) {
            append(";l=").append(s.loopInMs).append(',').append(s.loopOutMs)
            append(',').append(s.loopBeats?.toString() ?: "")
        }
        if (s.hotCues.isNotEmpty()) {
            append(";h=").append(s.hotCues.joinToString(",") { it?.toString() ?: "" })
        }
        s.userBpm?.let { append(";b=").append(it) }
    }

    /** Null for anything that is not a well-formed v1 record. Strict on
     * purpose: a half-parsed record could resurrect garbage cue positions. */
    fun decode(text: String?): SavedTrackState? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(';')
        if (parts.firstOrNull() != VERSION) return null
        var duration: Long? = null
        var cue: Long? = null
        var loopIn: Long? = null
        var loopOut: Long? = null
        var loopBeats: Float? = null
        var hot: List<Long?> = emptyList()
        var userBpm: Float? = null
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) return null
            val key = p.substring(0, eq)
            val v = p.substring(eq + 1)
            when (key) {
                "d" -> duration = v.toLongOrNull() ?: return null
                "c" -> cue = v.toLongOrNull() ?: return null
                "l" -> {
                    val f = v.split(',')
                    if (f.size != 3) return null
                    loopIn = f[0].toLongOrNull() ?: return null
                    loopOut = f[1].toLongOrNull() ?: return null
                    loopBeats = if (f[2].isEmpty()) null else (f[2].toFloatOrNull() ?: return null)
                }
                "h" -> hot = v.split(',').map { slot ->
                    if (slot.isEmpty()) null else (slot.toLongOrNull() ?: return null)
                }
                "b" -> userBpm = v.toFloatOrNull() ?: return null
                else -> { /* unknown field from a newer version: ignore it */ }
            }
        }
        val d = duration ?: return null
        if (d <= 0) return null
        return SavedTrackState(d, cue, loopIn, loopOut, loopBeats, hot, userBpm)
    }

    /**
     * The record cleaned up for a track of the real [durationMs], or null if
     * it must not be used at all (different file). Positions at or past the
     * end are dropped, and a loop whose out is not after its in is discarded
     * whole - the same invariants DeckViewModel enforces when arming.
     */
    fun validated(saved: SavedTrackState, durationMs: Long): SavedTrackState? {
        if (durationMs <= 0) return null
        if (kotlin.math.abs(saved.durationMs - durationMs) > DURATION_TOLERANCE_MS) return null
        fun ok(p: Long?): Long? = p?.takeIf { it in 0 until durationMs }
        val loopOk = saved.loopInMs != null && saved.loopOutMs != null &&
            saved.loopInMs in 0 until durationMs &&
            saved.loopOutMs > saved.loopInMs && saved.loopOutMs <= durationMs
        return SavedTrackState(
            durationMs = durationMs,
            cuePointMs = ok(saved.cuePointMs),
            loopInMs = if (loopOk) saved.loopInMs else null,
            loopOutMs = if (loopOk) saved.loopOutMs else null,
            loopBeats = if (loopOk) saved.loopBeats else null,
            hotCues = saved.hotCues.map(::ok),
            userBpm = saved.userBpm?.takeIf { BpmMath.isValid(it) },
        )
    }

    /**
     * Hot-cue slots to write. The pad count is a setting (4/8/16), so the
     * live list can be SHORTER than what was saved earlier at a larger
     * count; slots beyond the current count are carried over from the
     * previous record instead of being silently erased by a save made while
     * the setting was lower. Within the current count the live list always
     * wins (including a deliberate delete).
     */
    fun mergeHotCues(live: List<Long?>, previouslySaved: List<Long?>?): List<Long?> {
        if (previouslySaved == null || previouslySaved.size <= live.size) return live
        return live + previouslySaved.drop(live.size)
    }

    /** The cue state a deck holds right now / after a restore. */
    data class Live(
        val cuePointMs: Long?,
        val loopInMs: Long?,
        val loopOutMs: Long?,
        val loopBeats: Float?,
        val hotCues: List<Long?>,
    )

    /**
     * Fills a freshly loaded deck from its saved record, FIELD BY FIELD, never
     * overwriting something the DJ (or the "keep loops/cues across load"
     * setting) already put there:
     *  - hot cues: per slot, the live value wins, otherwise the saved one;
     *  - cue point: restored only if none is set;
     *  - loop: restored only if the deck has no loop at all.
     * (An all-or-nothing "restore only if everything is untouched" rule used to
     * skip the whole restore when ONE field had been carried over, and the deck
     * was then armed and wrote the carried state over the saved record.)
     */
    fun restore(live: Live, saved: SavedTrackState?, hotCueCount: Int): Live {
        if (saved == null) return live
        val savedPads = hotCuesForCount(saved.hotCues, hotCueCount)
        val pads = List(hotCueCount) { live.hotCues.getOrNull(it) ?: savedPads[it] }
        val loopFree = live.loopInMs == null && live.loopOutMs == null
        return Live(
            cuePointMs = live.cuePointMs ?: saved.cuePointMs,
            loopInMs = if (loopFree) saved.loopInMs else live.loopInMs,
            loopOutMs = if (loopFree) saved.loopOutMs else live.loopOutMs,
            loopBeats = if (loopFree) saved.loopBeats else live.loopBeats,
            hotCues = pads,
        )
    }

    /** The saved slots shaped to the current pad count: extra saved slots
     * are held back (see [mergeHotCues]), missing ones are empty. */
    fun hotCuesForCount(saved: List<Long?>, count: Int): List<Long?> = List(count) { saved.getOrNull(it) }
}

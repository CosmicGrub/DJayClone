package com.oblivion.djayclone

import android.app.Application
import android.content.Context

/** One deck's load state worth bringing back after the app was closed or killed. */
data class DeckSession(val uri: String, val title: String?, val positionMs: Long, val speed: Float)

/** What the app was doing when it last went to the background. Pure data. */
data class SessionSnapshot(
    val deckA: DeckSession?,
    val deckB: DeckSession?,
    val crossfader: Float,
    val syncLock: String?,
) {
    val isEmpty: Boolean get() = deckA == null && deckB == null
}

/**
 * Text codec for [SessionSnapshot], in the same spirit as TrackStateCodec: line based,
 * strict on decode (anything malformed yields null), values percent-escaped so a title
 * containing a tab or newline cannot break the record. Pure Kotlin, JVM-tested.
 */
object SessionCodec {
    private const val HEADER = "djsession1"

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '%' -> append("%25"); '\t' -> append("%09"); '\n' -> append("%0A"); '\r' -> append("%0D")
            else -> append(c)
        }
    }

    private fun unesc(s: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 3 > s.length) return null
                val code = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                sb.append(code.toChar())
                i += 3
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun encodeDeck(tag: String, d: DeckSession?): String? =
        d?.let { "$tag\t${esc(it.uri)}\t${esc(it.title ?: "")}\t${it.positionMs}\t${it.speed}" }

    fun encode(s: SessionSnapshot): String = listOfNotNull(
        HEADER,
        encodeDeck("A", s.deckA),
        encodeDeck("B", s.deckB),
        "M\t${s.crossfader}\t${esc(s.syncLock ?: "")}",
    ).joinToString("\n")

    fun decode(text: String?): SessionSnapshot? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return null
        var a: DeckSession? = null
        var b: DeckSession? = null
        var xf = 0.5f
        var lock: String? = null
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            when (f[0]) {
                "A", "B" -> {
                    if (f.size != 5) return null
                    val uri = unesc(f[1]) ?: return null
                    val title = unesc(f[2]) ?: return null
                    val pos = f[3].toLongOrNull() ?: return null
                    val speed = f[4].toFloatOrNull() ?: return null
                    if (uri.isBlank() || pos < 0 || speed.isNaN() || speed < DeckSpeedRange.MIN || speed > DeckSpeedRange.MAX) return null
                    val d = DeckSession(uri, title.ifEmpty { null }, pos, speed)
                    if (f[0] == "A") a = d else b = d
                }
                "M" -> {
                    if (f.size != 3) return null
                    xf = (f[1].toFloatOrNull() ?: return null).coerceIn(0f, 1f)
                    val l = unesc(f[2]) ?: return null
                    lock = l.takeIf { it == SyncController.B_FOLLOWS_A || it == SyncController.A_FOLLOWS_B }
                }
                else -> return null
            }
        }
        return SessionSnapshot(a, b, xf, lock)
    }
}

/** SharedPreferences home of the last session. Excluded from Auto Backup (it holds device-local URIs). */
class SessionStore private constructor(app: Application) {
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(s: SessionSnapshot) {
        if (s.isEmpty) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, SessionCodec.encode(s)).apply()
    }

    fun load(): SessionSnapshot? = SessionCodec.decode(prefs.getString(KEY, null))

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        const val PREFS_NAME = "djayclone_session"
        private const val KEY = "last"
        @Volatile private var instance: SessionStore? = null
        fun get(app: Application): SessionStore =
            instance ?: synchronized(this) { instance ?: SessionStore(app).also { instance = it } }
    }
}

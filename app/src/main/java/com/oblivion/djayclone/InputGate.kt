package com.oblivion.djayclone

/**
 * The one rule behind the per-deck input lock, so every input path (touch,
 * keyboard, RESET) asks the same question instead of each remembering to
 * check. Pure Kotlin: two boolean tables.
 */
object InputGate {
    enum class Kind {
        /** Any audible or state-changing deck input. Blocked on a locked deck. */
        NORMAL,
        /** The up-event of a hold (CUE preview, bend): always admitted, so
         * locking mid-hold can never strand a held action. */
        RELEASE,
        /** The lock button itself: always admitted, else there is no way out. */
        LOCK_TOGGLE,
        /** Changes only what is shown (time remaining, pad page): admitted. */
        DISPLAY_ONLY,
    }

    fun admit(deckLocked: Boolean, kind: Kind): Boolean = !deckLocked || kind != Kind.NORMAL

    /** Which decks a mix-level "reset tempo" may touch: locked decks keep theirs. */
    fun decksToReset(lockedA: Boolean, lockedB: Boolean): Set<Char> =
        buildSet { if (!lockedA) add('A'); if (!lockedB) add('B') }
}

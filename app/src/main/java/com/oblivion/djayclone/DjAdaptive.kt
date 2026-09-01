package com.oblivion.djayclone

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.debounce

// Standard Material window-size-class breakpoints. Hand-rolled rather than
// calling androidx.window.core.layout.WindowSizeClass: that class was
// verified via javap against the AAR this module ACTUALLY compiles against
// (window 1.3.0, strictly pinned - the newer 1.5.1 jar found in the shared
// Gradle cache belonged to the runtime classpath / another project's cache
// entry, not this module's compileClasspath) and confirmed absent from 1.3.0
// entirely - androidx.window.core.layout.WindowSizeClass, WindowWidthSizeClass,
// and WindowMetrics.widthDp/heightDp were all added in a later release than
// what this project actually resolves. WindowMetrics.bounds (pixels) is
// what 1.3.0 really offers, confirmed the same way.
private const val WIDTH_DP_MEDIUM_LOWER_BOUND = 600
private const val WIDTH_DP_EXPANDED_LOWER_BOUND = 840

/**
 * Stage 10: the two adaptive-layout signals, kept deliberately separate -
 * see the class-level rule below before touching either.
 *
 * DjLayoutClass answers "how much room" - COMPACT/MEDIUM/EXPANDED on the
 * standard 600dp/840dp Material thresholds. Originally a 2-way split
 * (Medium collapsed into Compact, per the Stage 10 pitch's stated "bespoke
 * tablet-portrait layout is out of scope" call) - promoted to a real third
 * tier after Stage 10a's own ground-truth logging caught the pitch's own
 * assumption being wrong: the Z Fold 5's inner screen, genuinely opened
 * flat, does NOT reach the 840dp Expanded threshold - confirmed on real
 * hardware in BOTH of its natural holds, book-portrait (~690dp wide,
 * ~829dp tall) and rotated-landscape (~829dp wide, ~690dp tall). Either
 * way the 2-way split would have silently rendered the old Compact
 * arrangement on the one screen this whole stage was partly motivated by -
 * both orientations now correctly land in MEDIUM instead, regardless of
 * which way the DJ is holding it.
 * MEDIUM shares Expanded's wider treatment for now (see MixerArrangement's
 * dispatcher) rather than getting a bespoke layout of its own - Stage 10a's
 * Expanded scope doesn't yet include the center-column/library-sidebar
 * features that would need the full >=840dp budget (that's Stage 10b), so
 * Medium and Expanded have nothing to meaningfully diverge on yet. This
 * same fix also corrected a second, previously-unnoticed misclassification:
 * the Tab S9 FE held in portrait (~823dp wide) was landing in Compact too,
 * under the old 2-way split - it now correctly lands in Medium as well.
 *
 * DjPosture answers "what physical shape" - and it CANNOT be derived from
 * DjLayoutClass, or from window size at all: androidx.window's WindowMetrics
 * reports the SAME full-inner-screen bounds for a Fold half-opened as for
 * fully opened flat (verified directly against the real androidx.window
 * 1.5.1 API before writing this - Android does not shrink the reported
 * window for a fold posture). Only FoldingFeature.state distinguishes them.
 * A half-opened Fold and a small tablet held portrait can report
 * near-identical window dimensions and still need entirely different
 * layouts - that's exactly the case size-class bucketing alone can't
 * resolve, which is why this is a second, independent signal rather than
 * one merged enum.
 *
 * THE RULE (applies everywhere these are consumed): DjLayoutClass picks the
 * whole-screen arrangement. DjPosture, when it's TableTop, overrides that
 * choice regardless of what size alone would have suggested. When Flat,
 * size alone decides.
 *
 * Stage 10c fulfills that rule for real: MixerArrangement now branches on
 * [LocalDjPosture] first, dispatching to MixerArrangementTableTop (in
 * MainActivity.kt) whenever it reports TableTop, before DjLayoutClass gets
 * a say.
 */
enum class DjLayoutClass { COMPACT, MEDIUM, EXPANDED }

sealed interface DjPosture {
    data object Flat : DjPosture
    /** HALF_OPENED with a horizontal hinge - the physical "tabletop/laptop"
     * posture. hingeBounds is in the same pixel coordinate space as the
     * window, real device bounds, not a hand-picked guess. */
    data class TableTop(val hingeBounds: Rect) : DjPosture
}

// Both default to the one shape this project has five stages of verified
// precedent on (Compact/Flat = today's phone-cover-screen UI, unchanged) -
// so a detection failure anywhere degrades silently to known-good, never to
// a crash or a wrong pane count.
val LocalDjLayoutClass = compositionLocalOf { DjLayoutClass.COMPACT }
val LocalDjPosture = compositionLocalOf<DjPosture> { DjPosture.Flat }

private const val TAG = "DjAdaptive"

/** Recomputed whenever Configuration changes (rotation, DeX window resize,
 * fold angle crossing a size-class boundary) - LocalConfiguration.current
 * is Compose's own recomposition trigger for exactly those events, so no
 * separate listener is needed here. */
@Composable
fun rememberDjLayoutClass(activity: Activity): DjLayoutClass {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(configuration, density) {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity)
        val widthDp = metrics.bounds.width() / density
        val heightDp = metrics.bounds.height() / density
        val layoutClass = when {
            widthDp >= WIDTH_DP_EXPANDED_LOWER_BOUND -> DjLayoutClass.EXPANDED
            widthDp >= WIDTH_DP_MEDIUM_LOWER_BOUND -> DjLayoutClass.MEDIUM
            else -> DjLayoutClass.COMPACT
        }
        // Stage 10a step 1: ground-truth logging - every number here
        // replaces an estimate the design pitch flagged as unverified.
        Log.d(
            TAG,
            "layoutClass: boundsPx=${metrics.bounds} density=$density " +
                "widthDp=$widthDp heightDp=$heightDp -> $layoutClass"
        )
        layoutClass
    }
}

// Stage 10c: TableTop now drives a real UI decision (the whole-screen
// arrangement swap) instead of just being logged, so the multi-emit-per-
// gesture risk the Stage 10a comment here used to defer is real now - a
// fold gesture reporting several rapid WindowLayoutInfo updates mid-motion
// would otherwise flicker the entire arrangement between COMPACT/MEDIUM/
// EXPANDED and TableTop while the DJ is still mid-fold, not yet settled
// into the posture they meant. 200ms is a starting point, not a measured
// figure - real hardware (an actual physical fold gesture, not something
// checkable by tapping a flat screen) is what should tune this if it ever
// feels laggy or flickery in practice.
private const val POSTURE_DEBOUNCE_MS = 200L

/** Collects WindowInfoTracker's Flow for the lifetime of the composition -
 * this is the live signal a fold-angle change (not just a discrete
 * Configuration change) delivers through. */
@OptIn(kotlinx.coroutines.FlowPreview::class) // Flow.debounce() - see POSTURE_DEBOUNCE_MS's doc above
@Composable
fun rememberDjPosture(activity: Activity): DjPosture {
    val posture by produceState<DjPosture>(initialValue = DjPosture.Flat, activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).debounce(POSTURE_DEBOUNCE_MS).collect { info ->
            val folding = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            val next = if (
                folding != null &&
                folding.state == FoldingFeature.State.HALF_OPENED &&
                folding.orientation == FoldingFeature.Orientation.HORIZONTAL
            ) {
                DjPosture.TableTop(folding.bounds)
            } else {
                DjPosture.Flat
            }
            Log.d(
                TAG,
                "posture: displayFeatures=${info.displayFeatures.size} " +
                    "folding.state=${folding?.state} folding.orientation=${folding?.orientation} " +
                    "folding.bounds=${folding?.bounds} -> $next"
            )
            value = next
        }
    }
    return posture
}

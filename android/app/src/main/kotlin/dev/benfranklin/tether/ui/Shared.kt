@file:OptIn(ExperimentalSharedTransitionApi::class)

package dev.benfranklin.tether.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/** The shared-transition scope of the current navigation destination, when there is one. */
class SharedScopes(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalSharedScopes = compositionLocalOf<SharedScopes?> { null }

/**
 * Marks this element as the same object across screens (e.g. the Tether mark on Home and
 * in Settings) so navigation morphs it in place, like iOS hero transitions. No-op outside a
 * navigation host (previews, screenshot tests) or with reduce-motion on.
 */
@Composable
fun Modifier.sharedHero(key: String, reduce: Boolean): Modifier {
    val s = LocalSharedScopes.current ?: return this
    if (reduce) return this
    return with(s.shared) {
        this@sharedHero.sharedElement(
            rememberSharedContentState(key), s.visibility,
            boundsTransform = { _: Rect, _: Rect -> spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow) },
        )
    }
}

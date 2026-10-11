package dev.benfranklin.tether.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.benfranklin.tether.ui.sharedHero
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.MonoCode
import dev.benfranklin.tether.ui.theme.Motion

/**
 * iOS-style large-title header (0.5.3): a back chevron and a trailing action on one row, then the
 * big title (shared with the Home panel it opened from) and an optional count.
 */
@Composable
fun LargeTitle(
    title: String,
    onBack: () -> Unit,
    heroKey: String? = null,
    count: Int? = null,
    trailing: @Composable () -> Unit = {},
) {
    val reduce = LocalReduceMotion.current
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, contentDescription = "Back", tint = Mono.Text)
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Box(if (heroKey != null) Modifier.sharedHero(heroKey, reduce) else Modifier) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Mono.Text, maxLines = 1)
            }
            if (count != null) {
                Spacer(Modifier.width(10.dp))
                AnimatedContent(count, label = "titleCount", transitionSpec = {
                    if (reduce) {
                        fadeIn(Motion.fade(true)) togetherWith fadeOut(Motion.fade(true))
                    } else {
                        val up = targetState > initialState
                        (slideInVertically(Motion.spring(false)) { if (up) it else -it } + fadeIn()) togetherWith
                            (slideOutVertically(Motion.spring(false)) { if (up) -it else it } + fadeOut())
                    }
                }) { n ->
                    Text("$n", style = MaterialTheme.typography.titleMedium, color = Mono.Muted, fontFamily = MonoCode,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
    }
}

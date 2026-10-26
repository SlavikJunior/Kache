package io.github.slavikjunior.kache.sample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material 3 theme for the sample.
 *
 * A plain light/dark scheme rather than Android's dynamic colour: the same UI runs on
 * Android, desktop and iOS, and a palette that changes per Android wallpaper would make
 * screenshots and the previews disagree with each other. The sample's job is to show the
 * cache, not a colour system.
 */
@Composable
fun KacheSampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

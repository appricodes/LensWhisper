package com.appricodes.lens_whisper.ui.components

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the status bar and navigation/gesture bar for as long as this is in composition — Explore
 * and Read Document want the full screen for the photo itself, with no system chrome competing for
 * space or attention. Restored automatically on dispose (leaving the screen), not just on the
 * Activity being destroyed, so returning to a normal screen doesn't inherit this.
 *
 * There is deliberately no system back gesture/button left to use while this is active — see
 * [ImmersiveBackButton], the replacement affordance both screens place in the bottom-right corner.
 */
@Composable
fun ImmersiveFullScreen() {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return

    SideEffect {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    DisposableEffect(Unit) {
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            WindowInsetsControllerCompat(activity.window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * A small back button fixed to the bottom-right corner, flush with the screen edges — the only way
 * out of an [ImmersiveFullScreen] screen, since the system back gesture/button is hidden there.
 * Deliberately a plain Unicode arrow glyph rather than a vector icon asset: this app has never
 * pulled in an icon library, and one character is all a "small back icon" needs to be recognizable.
 */
@Composable
fun BoxScope.ImmersiveBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "←", fontSize = 22.sp, color = Color.White)
    }
}

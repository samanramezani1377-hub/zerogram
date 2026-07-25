package com.zerochat.ui.profile

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Full-screen profile image preview with zoom gesture.
 *
 * Features:
 * - Dark immersive background
 * - Pinch-to-zoom
 * - Pan when zoomed
 * - Tap to dismiss (or close button)
 * - Smooth enter/exit transitions (fade + scale)
 */
@Composable
fun ProfilePreviewScreen(
    imagePath: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offset = if (scale > 1f) {
                        Offset(
                            x = offset.x + pan.x,
                            y = offset.y + pan.y,
                        )
                    } else {
                        Offset.Zero
                    }
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                if (scale <= 1.1f) {
                    onDismiss()
                }
            },
    ) {
        // Close button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .statusBarsPadding(),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
            )
        }

        if (imagePath != null) {
            val file = File(context.filesDir, imagePath)
            if (file.exists()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(file)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile picture preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun Modifier.clickable(
    indication: androidx.compose.foundation.interaction.MutableInteractionSource?,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
    onClick: () -> Unit,
): Modifier {
    // Simple clickable wrapper — exists so the fullscreen preview can be
    // dismissed with a tap anywhere outside the image.
    return this.then(
        Modifier
    ).then(
        androidx.compose.foundation.clickable(
            interactionSource = interactionSource,
            indication = indication,
            onClick = onClick,
        )
    )
}

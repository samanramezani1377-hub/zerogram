package com.zerochat.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Reusable circular profile image composable.
 *
 * Features:
 * - Loads from local file path
 * - Shows default avatar (person icon) when no image
 * - Loading indicator
 * - Error fallback
 * - Smooth crossfade animation
 * - Coil disk caching
 *
 * Usage:
 *   ProfileImage(imagePath = "/data/data/zerochat/profile/abc.webp", size = 64.dp)
 *   ProfileImage(imagePath = null, size = 48.dp) // shows default avatar
 *   ProfileImage(imagePath = path, size = 96.dp, onClick = { showPreview() })
 */
@Composable
fun ProfileImage(
    imagePath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    borderWidth: Dp = 0.dp,
) {
    val context = LocalContext.current
    val baseModifier = modifier
        .size(size)
        .clip(CircleShape)

    val clickableModifier = if (onClick != null) {
        baseModifier.clickable { onClick() }
    } else {
        baseModifier
    }

    val finalModifier = if (borderWidth > 0.dp) {
        clickableModifier.border(borderWidth, borderColor, CircleShape)
    } else {
        clickableModifier
    }

    if (imagePath != null) {
        val file = File(context.filesDir, imagePath)
        if (file.exists()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .crossfade(true)
                    .memoryCacheKey(imagePath)
                    .diskCacheKey(imagePath)
                    .build(),
                contentDescription = "Profile picture",
                modifier = finalModifier,
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(size * 0.4f),
                            strokeWidth = 2.dp,
                        )
                    }
                },
                error = {
                    DefaultAvatar(
                        modifier = Modifier.matchParentSize(),
                        size = size,
                    )
                },
            )
        } else {
            DefaultAvatar(modifier = finalModifier, size = size)
        }
    } else {
        DefaultAvatar(modifier = finalModifier, size = size)
    }
}

@Composable
private fun DefaultAvatar(
    modifier: Modifier,
    size: Dp,
) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Default avatar",
            modifier = Modifier.size(size * 0.55f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

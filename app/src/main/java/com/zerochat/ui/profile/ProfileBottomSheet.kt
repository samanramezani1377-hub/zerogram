package com.zerochat.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for profile picture actions.
 *
 * Options:
 * - Change profile photo
 * - Remove profile photo
 * - View profile photo
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePictureBottomSheet(
    hasProfilePicture: Boolean,
    onDismiss: () -> Unit,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onViewPhoto: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            // Header
            Text(
                text = "Profile Photo",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Change photo
            NavigationDrawerItem(
                icon = {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                },
                label = { Text("Change Profile Photo") },
                selected = false,
                onClick = {
                    onChangePhoto()
                    onDismiss()
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            // View photo (only if has one)
            if (hasProfilePicture) {
                NavigationDrawerItem(
                    icon = {
                        Icon(Icons.Default.OpenInFull, contentDescription = null)
                    },
                    label = { Text("View Profile Photo") },
                    selected = false,
                    onClick = {
                        onViewPhoto()
                        onDismiss()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                // Remove photo
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                NavigationDrawerItem(
                    icon = {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = {
                        Text(
                            "Remove Profile Photo",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    selected = false,
                    onClick = {
                        onRemovePhoto()
                        onDismiss()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

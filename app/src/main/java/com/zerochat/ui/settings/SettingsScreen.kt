package com.zerochat.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.BuildConfig
import com.zerochat.ui.profile.ProfileImage
import com.zerochat.ui.profile.ProfilePictureBottomSheet
import com.zerochat.ui.profile.ProfilePreviewScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Photo Picker — Android 13+ uses the built-in Photo Picker,
    // pre-13 uses a compatible fallback via PickVisualMedia.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.changeProfilePhoto(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── Profile Picture + Identity ───────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Profile image — tap to open bottom sheet
                    ProfileImage(
                        imagePath = uiState.profileImagePath,
                        size = 72.dp,
                        borderColor = MaterialTheme.colorScheme.primary,
                        borderWidth = 2.dp,
                        onClick = { viewModel.showBottomSheet() },
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Your Identity",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ID: ${uiState.myFingerprint}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Public Key: ${uiState.myPublicKey.take(32)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Encryption info
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "End-to-End Encryption",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "All messages are encrypted using the Signal Protocol:\n" +
                                "• X3DH key agreement\n" +
                                "• Double Ratchet for per-message forward secrecy\n" +
                                "• Curve25519 + AES-256-GCM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Network info
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Network",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Local IPs: ${uiState.localIps.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Error snackbar
            if (uiState.error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // App version
            Text(
                text = "ZeroGram v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    // ── Bottom Sheet ────────────────────────────────────────────
    if (uiState.showBottomSheet) {
        ProfilePictureBottomSheet(
            hasProfilePicture = uiState.hasProfilePicture,
            onDismiss = { viewModel.hideBottomSheet() },
            onChangePhoto = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onRemovePhoto = { viewModel.requestRemovePhoto() },
            onViewPhoto = { viewModel.showPreview() },
        )
    }

    // ── Remove Confirmation Dialog ──────────────────────────────
    if (uiState.showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRemoveDialog() },
            title = { Text("Remove Profile Photo?") },
            text = { Text("This will remove your profile picture. Other users will no longer see it.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRemovePhoto() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRemoveDialog() }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Full-Screen Preview ────────────────────────────────────
    if (uiState.showPreview) {
        ProfilePreviewScreen(
            imagePath = uiState.profileImagePath,
            onDismiss = { viewModel.hidePreview() },
        )
    }
}

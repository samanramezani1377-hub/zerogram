package com.zerochat.ui.contacts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.data.model.Peer
import com.zerochat.data.model.TransportMode
import com.zerochat.ui.profile.ProfileImage
import com.zerochat.ui.theme.TelegramBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<Peer?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZeroGram", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = onNavigateToDiscovery) {
                        Icon(Icons.Default.Search, contentDescription = "Search peers")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        if (uiState.contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        null,
                        Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No conversations yet", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap search to find nearby peers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(
                        onClick = onNavigateToDiscovery,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = TelegramBlue, contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Default.PersonAdd, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Find Peers")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(uiState.contacts, key = { it.fingerprint }) { peer ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                showDeleteDialog = peer
                                false // don't auto-dismiss
                            } else true
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val color by animateColorAsState(
                                when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFE53935)
                                    else -> Color.Transparent
                                },
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp)
                                    .background(color, RoundedCornerShape(0.dp)),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                                    Icon(Icons.Default.Delete, null, tint = Color.White)
                                }
                            }
                        },
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                    ) {
                        ChatListItem(peer = peer, onClick = { onNavigateToChat(peer.fingerprint) })
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { peer ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Chat?") },
            text = {
                Text("Delete conversation with ${peer.displayName.ifBlank { formatFingerprint(peer.fingerprint) }}? This will remove all messages.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePeerAndMessages(peer.fingerprint)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE53935)),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChatListItem(peer: Peer, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileImage(imagePath = peer.profileImagePath, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        peer.displayName.ifBlank { formatFingerprint(peer.fingerprint) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatLastSeen(peer.lastSeen),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (peer.preferredTransport) {
                            TransportMode.LAN -> "Local Network"
                            TransportMode.WAN -> "Internet"
                            TransportMode.UNKNOWN -> "Tap to connect"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(8.dp).background(
                            when (peer.preferredTransport) {
                                TransportMode.LAN -> Color(0xFF4CAF50)
                                TransportMode.WAN -> TelegramBlue
                                TransportMode.UNKNOWN -> Color(0xFFBDBDBD)
                            },
                            shape = RoundedCornerShape(4.dp),
                        ),
                    )
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 80.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun formatFingerprint(fp: String) =
    if (fp.length >= 12) fp.take(6) + "..." + fp.takeLast(4) else fp

private fun formatLastSeen(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        else -> java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }
}

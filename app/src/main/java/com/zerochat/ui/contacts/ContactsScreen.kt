package com.zerochat.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.data.model.Peer
import com.zerochat.data.model.TransportMode
import com.zerochat.ui.profile.ProfileImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZeroGram") },
                actions = {
                    IconButton(onClick = onNavigateToDiscovery) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add peer")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToDiscovery,
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                text = { Text("Find Peers") },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // ── My Identity Card — with profile picture ──────────
            MyIdentityCard(
                myId = uiState.myId,
                myIp = uiState.myIp,
                profileImagePath = uiState.myProfileImagePath,
                onNavigateToSettings = onNavigateToSettings,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Contacts",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (uiState.contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No contacts yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap 'Find Peers' to discover nearby devices\nor add a peer manually by ID",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(uiState.contacts, key = { it.fingerprint }) { peer ->
                        ContactItem(
                            peer = peer,
                            onClick = { onNavigateToChat(peer.fingerprint) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MyIdentityCard(
    myId: String,
    myIp: String,
    profileImagePath: String?,
    onNavigateToSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileImage(
                imagePath = profileImagePath,
                size = 48.dp,
                onClick = { onNavigateToSettings() },
                borderColor = MaterialTheme.colorScheme.onPrimaryContainer,
                borderWidth = 1.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "My Identity",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = myId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (myIp.isNotEmpty()) {
                    Text(
                        text = "IP: $myIp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactItem(peer: Peer, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = peer.displayName.ifBlank { formatFingerprint(peer.fingerprint) },
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Column {
                Text(text = formatFingerprint(peer.fingerprint))
                if (peer.ipAddress.isNotEmpty()) {
                    Text(
                        text = "${peer.ipAddress}:${peer.port}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = when (peer.preferredTransport) {
                        TransportMode.LAN -> "🖧 LAN"
                        TransportMode.WAN -> "🌐 Internet"
                        TransportMode.UNKNOWN -> "Unknown"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = {
            // Show peer's profile picture if stored
            ProfileImage(
                imagePath = peer.profileImagePath,
                size = 44.dp,
            )
        },
    )
}

private fun formatFingerprint(fp: String): String {
    return if (fp.length >= 12) {
        "${fp.take(8)}…${fp.takeLast(4)}"
    } else {
        fp
    }
}

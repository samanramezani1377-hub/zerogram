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
import com.zerochat.data.model.ConnectionStatus
import com.zerochat.data.model.Peer
import com.zerochat.ui.theme.OnlineIndicator
import com.zerochat.ui.theme.OfflineIndicator

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
                title = { Text("ZeroChat") },
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
        if (uiState.contacts.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
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
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                // My ID card
                item {
                    MydentityCard(
                        myId = uiState.myId,
                        myIp = uiState.myIp,
                    )
                }

                item {
                    Text(
                        text = "Contacts",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                items(uiState.contacts, key = { it.id }) { peer ->
                    ContactItem(
                        peer = peer,
                        onClick = { onNavigateToChat(peer.identity.fingerprint) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MydentityCard(myId: String, myIp: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "My Identity",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
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

@Composable
private fun ContactItem(peer: Peer, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = peer.identity.displayName,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Column {
                Text(text = peer.identity.fingerprint.take(8))
                if (peer.lastKnownIp != null) {
                    Text(
                        text = "${peer.lastKnownIp}:${peer.lastKnownPort}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        leadingContent = {
            Badge(
                containerColor = when (peer.connectionStatus) {
                    ConnectionStatus.CONNECTED -> OnlineIndicator
                    else -> OfflineIndicator
                },
                modifier = Modifier.size(12.dp),
            ) {}
        },
    )
}

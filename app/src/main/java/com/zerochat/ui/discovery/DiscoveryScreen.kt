package com.zerochat.ui.discovery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.network.lan.LanPeer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onNavigateBack: () -> Unit,
    onPeerSelected: (String) -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddPeerDialog by remember { mutableStateOf(false) }
    var manualPeerId by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Peers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Refresh button — force rescan
                    IconButton(onClick = { viewModel.startDiscovery() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showAddPeerDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add manually")
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
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Discover") },
                    icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("PIN Code") },
                    icon = { Icon(Icons.Default.Pin, contentDescription = null) },
                )
            }

            // Error banner
            AnimatedVisibility(
                visible = uiState.error != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.error ?: "",
                            maxLines = 5,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Manual IP entry dialog
            if (showAddPeerDialog) {
                AlertDialog(
                    onDismissRequest = { showAddPeerDialog = false },
                    title = { Text("Add Peer") },
                    text = {
                        OutlinedTextField(
                            value = manualPeerId,
                            onValueChange = { manualPeerId = it },
                            label = { Text("Peer ID or IP Address") },
                            placeholder = { Text("e.g. ZC:abc123… or 192.168.1.5") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.connectManually(manualPeerId)
                            showAddPeerDialog = false
                            manualPeerId = ""
                        }) { Text("Connect") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showAddPeerDialog = false
                        }) { Text("Cancel") }
                    }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> ScanTab(uiState, viewModel, onPeerSelected)
                1 -> PinCodeTab(uiState, viewModel)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Tab 0: WiFi / mDNS scan
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ScanTab(
    uiState: DiscoveryUiState,
    viewModel: DiscoveryViewModel,
    onPeerSelected: (String) -> Unit,
) {
    when {
        // Only show spinner on FIRST load — silent refresh doesn't set isDiscovering
        uiState.isDiscovering && uiState.peers.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Searching for nearby peers...",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Make sure WiFi is on "Make sure WiFi is enabled on both devices" both devices are connected to the same network",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        uiState.peers.isEmpty() && !uiState.isDiscovering -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PersonSearch,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No peers found",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Make sure WiFi is on "Make sure WiFi is enabled on both devices" both devices are connected to the same network",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.startDiscovery() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Now")
                    }
                }
            }
        }

        else -> {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (uiState.isDiscovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isDiscovering)
                                    "Scanning..."
                                else
                                    "LAN Mode — ${uiState.peers.size} device(s) nearby",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                items(uiState.peers, key = { it.deviceId + it.ipAddress }) { peer ->
                    DiscoveredPeerItem(
                        peer = peer,
                        onClick = {
                            val fp = viewModel.resolveFingerprint(peer)
                            onPeerSelected(fp)
                        },
                        onConnect = { viewModel.connectToPeer(peer) },
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Tab 1: PIN Code
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PinCodeTab(
    uiState: DiscoveryUiState,
    viewModel: DiscoveryViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // My PIN
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "My Code",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Share this code with the other device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = uiState.myPinCode,
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.isAdvertisingPin) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.WifiTethering,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Broadcasting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    TextButton(onClick = { viewModel.startPinAdvertising() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start Broadcasting")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        // Enter Other's PIN
        Text(
            "Enter Other Device's Code",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Type the 8-digit code shown on the other device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = uiState.lookupPin,
            onValueChange = { viewModel.updateLookupPin(it) },
            label = { Text("PIN Code") },
            placeholder = { Text("12345678") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                if (uiState.lookupPin.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateLookupPin("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val finalPin = uiState.lookupPin.padStart(8, '0')
                viewModel.lookupPinCode(finalPin)
            },
            enabled = uiState.lookupPin.length >= 8 && !uiState.isLookingUp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isLookingUp) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Searching…")
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Find Device")
            }
        }

        // Resolved peer info
        AnimatedVisibility(visible = uiState.resolvedPeer != null) {
            uiState.resolvedPeer?.let { peer ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Device Found!",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                "${peer.displayName.ifBlank { "Unknown" }} @ ${peer.ipAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredPeerItem(
    peer: LanPeer,
    onClick: () -> Unit,
    onConnect: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = peer.displayName.ifBlank { "Unknown Device" },
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Column {
                if (peer.ipAddress.isNotEmpty()) {
                    Text(text = "IP: ${peer.ipAddress}:${peer.port}")
                }
                Text(
                    text = "via ${peer.discoveryMethod}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Button(onClick = onConnect) { Text("Connect") }
        },
    )
}

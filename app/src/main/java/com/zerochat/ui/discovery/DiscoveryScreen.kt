package com.zerochat.ui.discovery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.zerochat.ui.theme.TelegramBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onNavigateBack: () -> Unit,
    onPeerSelected: (String) -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.connectedPeerFingerprint) {
        uiState.connectedPeerFingerprint?.let { fp ->
            onPeerSelected(fp)
            viewModel.clearConnectedFingerprint()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = TelegramBlue,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Nearby") },
                    icon = { Icon(Icons.Default.Wifi, null) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("PIN Code") },
                    icon = { Icon(Icons.Default.Pin, null) },
                )
            }

            AnimatedVisibility(
                visible = uiState.error != null,
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            uiState.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> NearbyTab(uiState, viewModel)
                1 -> PinCodeTab(uiState, viewModel)
            }
        }
    }
}

@Composable
private fun NearbyTab(uiState: DiscoveryUiState, viewModel: DiscoveryViewModel) {
    when {
        uiState.isDiscovering && uiState.peers.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TelegramBlue)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Searching for nearby peers...", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Make sure WiFi is enabled on both devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        uiState.peers.isEmpty() && !uiState.isDiscovering -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PersonSearch, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No peers found", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Make sure WiFi is enabled on both devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = { viewModel.startDiscovery() },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = TelegramBlue, contentColor = androidx.compose.ui.graphics.Color.White),
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Now")
                    }
                }
            }
        }
        else -> {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(uiState.peers, key = { it.deviceId + it.ipAddress }) { peer ->
                    PeerItem(peer = peer, onConnect = { viewModel.connectToPeer(peer) })
                }
            }
        }
    }
}

@Composable
private fun PeerItem(peer: LanPeer, onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.displayName.ifBlank { "Unknown Device" }, fontWeight = FontWeight.Medium)
                if (peer.ipAddress.isNotEmpty()) {
                    Text("${peer.ipAddress}:${peer.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("via ${peer.discoveryMethod}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onConnect) { Text("Connect") }
        }
    }
}

@Composable
private fun PinCodeTab(uiState: DiscoveryUiState, viewModel: DiscoveryViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // My PIN card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TelegramBlue.copy(alpha = 0.1f)),
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("My Code", style = MaterialTheme.typography.titleMedium, color = TelegramBlue, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Share this with the other device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = uiState.myPinCode,
                    fontSize = 42.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    color = TelegramBlue,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        Text("Enter Other Device Code", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Type the 8-digit code shown on the other device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = uiState.lookupPin,
            onValueChange = { viewModel.updateLookupPin(it) },
            label = { Text("PIN Code") },
            placeholder = { Text("12345678") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { viewModel.lookupPinCode(uiState.lookupPin.padStart(8, '0')) },
            enabled = uiState.lookupPin.length >= 8 && !uiState.isLookingUp,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = TelegramBlue),
        ) {
            if (uiState.isLookingUp) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Searching...")
            } else {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Find Device")
            }
        }

        // Resolved peer
        AnimatedVisibility(visible = uiState.resolvedPeer != null) {
            uiState.resolvedPeer?.let { peer ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Device Found!", style = MaterialTheme.typography.titleSmall)
                            Text("${peer.displayName.ifBlank { "Unknown" }} @ ${peer.ipAddress}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

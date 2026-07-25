package com.zerochat.ui.requests

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.data.model.ConnectionRequest
import com.zerochat.data.model.RequestStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestInboxScreen(
    onNavigateBack: () -> Unit,
    viewModel: RequestInboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Requests") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.requests.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No connection requests",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.requests, key = { it.id }) { request ->
                    RequestCard(
                        request = request,
                        onAccept = { viewModel.acceptRequest(request) },
                        onReject = { viewModel.rejectRequest(request.id) },
                        onBlock = { viewModel.blockRequest(request) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestCard(
    request: ConnectionRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onBlock: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = request.senderDisplayName.ifBlank { request.senderFingerprint.take(12) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "From: ${request.senderIp}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (request.status) {
                    RequestStatus.PENDING -> "⏳ Pending"
                    RequestStatus.ACCEPTED -> "✅ Accepted"
                    RequestStatus.REJECTED -> "❌ Rejected"
                    RequestStatus.BLOCKED -> "🚫 Blocked"
                    RequestStatus.EXPIRED -> "⏰ Expired"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (request.status == RequestStatus.PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onAccept) {
                        Text("Accept")
                    }
                    OutlinedButton(onClick = onReject) {
                        Text("Reject")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onBlock) {
                        Text("Block", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

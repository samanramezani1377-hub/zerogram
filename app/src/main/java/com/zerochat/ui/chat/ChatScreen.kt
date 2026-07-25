package com.zerochat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.data.model.TransportMode
import com.zerochat.ui.profile.ProfileImage
import com.zerochat.ui.theme.TelegramBlue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    peerFingerprint: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.sendMedia(it.toString()) }
    }

    LaunchedEffect(peerFingerprint) {
        viewModel.initialize(peerFingerprint)
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileImage(imagePath = uiState.peerProfileImagePath, size = 36.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                uiState.peerName,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = when (uiState.transportMode) {
                                    TransportMode.LAN -> if (uiState.isConnected) "online via LAN" else "connecting..."
                                    TransportMode.WAN -> "online via Internet"
                                    TransportMode.UNKNOWN -> if (uiState.isConnected) "online" else "connecting..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = if (uiState.isConnected) Color(0xFF4CAF50)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Encrypted",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
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
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.clearError() },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Surface(
                    shadowElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach file",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            placeholder = {
                                Text(
                                    "Message",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 5,
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            singleLine = true,
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        val canSend = messageText.isNotBlank()
                        IconButton(
                            onClick = {
                                if (messageText.isNotBlank()) {
                                    viewModel.sendMessage(messageText.trim())
                                    messageText = ""
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Send",
                                tint = if (canSend) TelegramBlue
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = TelegramBlue) }
            }

            uiState.messages.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ProfileImage(imagePath = uiState.peerProfileImagePath, size = 80.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(uiState.peerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            "End-to-end encrypted",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .imePadding(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            onDelete = { viewModel.deleteMessage(message.id) },
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("message", message.plainContent))
                            },
                            onRetry = { viewModel.retryMessage(message.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onDelete: () -> Unit = {},
    onCopy: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val isSent = message.isOutgoing
    val isFailed = message.status == MessageStatus.FAILED

    val bubbleColor = if (isSent) TelegramBlue
                      else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSent) Color.White
                    else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (isSent) 18.dp else 4.dp,
                        bottomEnd = if (isSent) 4.dp else 18.dp,
                    )
                )
                .background(bubbleColor)
                .combinedClickable(
                    onClick = { if (isFailed) onRetry() },
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = message.plainContent,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                    if (isSent) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when (message.status) {
                            MessageStatus.PENDING, MessageStatus.SENDING ->
                                Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = textColor.copy(alpha = 0.7f))
                            MessageStatus.SENT ->
                                Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = textColor.copy(alpha = 0.7f))
                            MessageStatus.DELIVERED ->
                                Icon(Icons.Default.DoneAll, null, Modifier.size(14.dp), tint = textColor.copy(alpha = 0.7f))
                            MessageStatus.READ ->
                                Icon(Icons.Default.DoneAll, null, Modifier.size(14.dp), tint = Color(0xFF4FC3F7))
                            MessageStatus.FAILED ->
                                Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = Color(0xFFE53935))
                        }
                    }
                }
                if (isFailed) {
                    Text(
                        "Tap to retry",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // Long-press menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    showMenu = false
                    onCopy()
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = Color(0xFFE53935)) },
                onClick = {
                    showMenu = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = Color(0xFFE53935))
                },
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

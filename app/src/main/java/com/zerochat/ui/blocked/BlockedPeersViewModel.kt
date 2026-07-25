package com.zerochat.ui.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.data.model.BlockedPeer
import com.zerochat.domain.BlockedPeerRepository
import com.zerochat.domain.ConnectionRequestUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class BlockedPeersUiState(
    val blockedPeers: List<BlockedPeer> = emptyList(),
)

@HiltViewModel
class BlockedPeersViewModel @Inject constructor(
    private val blockedRepo: BlockedPeerRepository,
    private val connectionRequestUseCase: ConnectionRequestUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlockedPeersUiState())
    val uiState: StateFlow<BlockedPeersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            blockedRepo.getAllBlocked().collect { blocked ->
                _uiState.update { it.copy(blockedPeers = blocked) }
            }
        }
    }

    fun unblockPeer(fingerprint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionRequestUseCase.unblockPeer(fingerprint)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unblock peer")
            }
        }
    }
}

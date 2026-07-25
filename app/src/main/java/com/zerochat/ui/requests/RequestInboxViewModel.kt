package com.zerochat.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.data.model.ConnectionRequest
import com.zerochat.domain.ConnectionRequestRepository
import com.zerochat.domain.ConnectionRequestUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class RequestInboxUiState(
    val requests: List<ConnectionRequest> = emptyList(),
)

@HiltViewModel
class RequestInboxViewModel @Inject constructor(
    private val requestRepo: ConnectionRequestRepository,
    private val connectionRequestUseCase: ConnectionRequestUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RequestInboxUiState())
    val uiState: StateFlow<RequestInboxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            requestRepo.getAllRequests().collect { requests ->
                _uiState.update { it.copy(requests = requests) }
            }
        }
    }

    fun acceptRequest(request: ConnectionRequest) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionRequestUseCase.acceptRequest(request)
            } catch (e: Exception) {
                Timber.w(e, "Failed to accept request")
            }
        }
    }

    fun rejectRequest(requestId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionRequestUseCase.rejectRequest(requestId)
            } catch (e: Exception) {
                Timber.w(e, "Failed to reject request")
            }
        }
    }

    fun blockRequest(request: ConnectionRequest) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionRequestUseCase.blockRequest(request)
            } catch (e: Exception) {
                Timber.w(e, "Failed to block request")
            }
        }
    }
}

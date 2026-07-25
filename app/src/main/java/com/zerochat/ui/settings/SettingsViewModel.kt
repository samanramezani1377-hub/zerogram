package com.zerochat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.crypto.CryptoEngine
import com.zerochat.network.lan.LanTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val myFingerprint: String = "Loading...",
    val myPublicKey: String = "",
    val localIps: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val lanTransport: LanTransport,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val fingerprint = cryptoEngine.getLocalFingerprint()
            val publicKey = cryptoEngine.getPublicIdentityKey()
            val ips = lanTransport.getLocalAddresses()
            _uiState.update {
                it.copy(
                    myFingerprint = "ZC:$fingerprint",
                    myPublicKey = publicKey,
                    localIps = ips,
                )
            }
        }
    }
}

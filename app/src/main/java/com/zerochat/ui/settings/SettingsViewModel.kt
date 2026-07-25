package com.zerochat.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.crypto.CryptoEngine
import com.zerochat.domain.profile.ProfileImageRepository
import com.zerochat.domain.profile.ProfileImageUseCase
import com.zerochat.domain.profile.ProfileSyncHandler
import com.zerochat.network.lan.LanTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SettingsUiState(
    val myFingerprint: String = "Loading...",
    val myPublicKey: String = "",
    val localIps: List<String> = emptyList(),
    // ── Profile Picture ─────────────────────────────────────────
    val profileImagePath: String? = null,
    val profileImageHash: String? = null,
    val hasProfilePicture: Boolean = false,
    val isProcessingImage: Boolean = false,
    val showBottomSheet: Boolean = false,
    val showPreview: Boolean = false,
    val showRemoveConfirm: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val lanTransport: LanTransport,
    private val profileImageUseCase: ProfileImageUseCase,
    private val profileImageRepository: ProfileImageRepository,
    private val profileSyncHandler: ProfileSyncHandler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var localFingerprint = ""

    init {
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch {
            val fingerprint = cryptoEngine.getLocalFingerprint()
            localFingerprint = fingerprint

            val publicKey = cryptoEngine.getPublicIdentityKey()
            val ips = lanTransport.getLocalAddresses()

            _uiState.update {
                it.copy(
                    myFingerprint = "ZC:$fingerprint",
                    myPublicKey = publicKey,
                    localIps = ips,
                )
            }

            profileImageRepository.getLocalProfile(fingerprint).collect { profile ->
                _uiState.update {
                    it.copy(
                        profileImagePath = profile?.profileImagePath,
                        profileImageHash = profile?.profileImageHash,
                        hasProfilePicture = profile?.profileImagePath != null,
                    )
                }
            }
        }
    }

    fun showBottomSheet() {
        _uiState.update { it.copy(showBottomSheet = true, error = null) }
    }

    fun hideBottomSheet() {
        _uiState.update { it.copy(showBottomSheet = false) }
    }

    fun changeProfilePhoto(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingImage = true, error = null) }

            try {
                val result = profileImageUseCase(localFingerprint, uri)
                result.fold(
                    onSuccess = { hash ->
                        _uiState.update { it.copy(isProcessingImage = false) }
                        val profile =
                            profileImageRepository.getLocalProfileOnce(localFingerprint)
                        profile?.let { p ->
                            val size = try {
                                p.profileImagePath?.let { path ->
                                    profileImageUseCase.loadImage(path)?.size ?: 0
                                } ?: 0
                            } catch (_: Exception) { 0 }
                            profileSyncHandler.broadcastProfileUpdate(
                                imageId = p.profileImageId ?: "",
                                imageHash = p.profileImageHash ?: "",
                                imageSize = size,
                            )
                        }
                    },
                    onFailure = { e ->
                        val msg = when {
                            e is IllegalArgumentException -> e.message
                            e is IllegalStateException -> e.message
                            else -> "Failed to set profile photo"
                        }
                        _uiState.update {
                            it.copy(isProcessingImage = false, error = msg)
                        }
                    },
                )
            } catch (e: Exception) {
                Timber.e(e, "changeProfilePhoto failed")
                _uiState.update {
                    it.copy(
                        isProcessingImage = false,
                        error = e.message ?: "Something went wrong"
                    )
                }
            }
        }
    }

    fun requestRemovePhoto() {
        _uiState.update { it.copy(showRemoveConfirm = true) }
    }

    fun dismissRemoveDialog() {
        _uiState.update { it.copy(showRemoveConfirm = false) }
    }

    fun confirmRemovePhoto() {
        viewModelScope.launch {
            _uiState.update { it.copy(showRemoveConfirm = false, error = null) }
            val result = profileImageUseCase.removeImage(localFingerprint)
            result.fold(
                onSuccess = { profileSyncHandler.broadcastProfileRemoved() },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Failed to remove profile photo")
                    }
                },
            )
        }
    }

    fun showPreview() {
        _uiState.update { it.copy(showPreview = true) }
    }

    fun hidePreview() {
        _uiState.update { it.copy(showPreview = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

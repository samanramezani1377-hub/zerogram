package com.zerochat

import android.app.Application
import com.zerochat.crypto.CryptoEngine
import com.zerochat.domain.IncomingMessageHandler
import com.zerochat.network.transport.TransportRouter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ZeroChat Application entry point.
 *
 * Initializes:
 * - Timber (logging) in debug builds
 * - TransportRouter (LAN + WAN listeners)
 * - IncomingMessageHandler (decrypt and persist incoming messages)
 *
 * All services run on a single application-scoped CoroutineScope
 * backed by Dispatchers.IO. SupervisorJob ensures one failing
 * service does not bring down the others.
 */
@HiltAndroidApp
class ZeroChatApp : Application() {

    @Inject lateinit var transportRouter: TransportRouter
    @Inject lateinit var incomingMessageHandler: IncomingMessageHandler
    @Inject lateinit var cryptoEngine: CryptoEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        if (runCatching { BuildConfig.DEBUG }.getOrDefault(true)) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("ZeroChat v${runCatching { BuildConfig.VERSION_NAME }.getOrDefault("0.1.0")} starting up")

        appScope.launch {
            try {
                val fp = cryptoEngine.getLocalFingerprint()
                transportRouter.setLocalFingerprint(fp)
                transportRouter.start()
                Timber.i("TransportRouter started with fingerprint $fp")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start TransportRouter")
            }
        }

        appScope.launch {
            try {
                incomingMessageHandler.startListening()
                Timber.i("IncomingMessageHandler started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start IncomingMessageHandler")
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.launch {
            transportRouter.stop()
        }
    }
}

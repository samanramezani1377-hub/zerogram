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

        // Global crash handler — prevents app from instantly dying
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception in thread ${thread.name}")
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val isDebug = runCatching { BuildConfig.DEBUG }.getOrDefault(false)
        if (isDebug) { Timber.plant(Timber.DebugTree()) }
        val versionName = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("0.1.0")
        Timber.i("ZeroGram v$versionName starting up")

        appScope.launch {
            try {
                // Generate or load identity before starting transports
                cryptoEngine.generateIdentity()
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
            runCatching { transportRouter.stop() }
            runCatching { incomingMessageHandler.stop() }
        }
    }
}

package com.zerochat

import android.app.Application
import com.zerochat.crypto.CryptoEngine
import com.zerochat.domain.IncomingMessageHandler
import com.zerochat.network.transport.TransportRouter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ZeroChatApp : Application() {

    @Inject lateinit var transportRouter: TransportRouter
    @Inject lateinit var incomingMessageHandler: IncomingMessageHandler
    @Inject lateinit var cryptoEngine: CryptoEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception in thread ${thread.name}")
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val isDebug = runCatching { BuildConfig.DEBUG }.getOrDefault(false)
        if (isDebug) { Timber.plant(Timber.DebugTree()) }
        val versionName = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("0.1.0")
        Timber.i("ZeroGram v$versionName starting up")

        // STEP 1: Generate identity
        appScope.launch {
            try {
                cryptoEngine.generateIdentity()
                val fp = cryptoEngine.getLocalFingerprint()
                Timber.i("Identity ready: $fp")
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate identity")
            }
        }

        // STEP 2: Start TransportRouter (opens listener)
        appScope.launch {
            try {
                // Wait a bit for identity to generate
                delay(500)
                val fp = cryptoEngine.getLocalFingerprint()
                transportRouter.setLocalFingerprint(fp)
                transportRouter.start()
                Timber.i("TransportRouter started with fingerprint $fp")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start TransportRouter")
            }
        }

        // STEP 3: Start IncomingMessageHandler AFTER TransportRouter is ready
        appScope.launch {
            try {
                // Give TransportRouter time to start listening
                delay(1500)
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
            runCatching { incomingMessageHandler.stop() }
            runCatching { transportRouter.stop() }
        }
    }
}

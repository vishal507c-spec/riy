package com.vishal.riy.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Automatic replay when connectivity returns.
 *
 * Registers a [ConnectivityManager.NetworkCallback] (no polling, respects
 * lifecycle/background limits). On available/validated network, flushes the
 * persisted pending-sync queue via [DriveBackupManager.flushPendingSync].
 *
 * Caller must call [unregister] (e.g. Activity.onStop) to avoid leaks.
 *
 * Cloned from the reference `BackupNetworkObserver` (behavior identical).
 */
class BackupNetworkObserver(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val backup: DriveBackupManager,
) {
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mutex = Mutex()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                // Coalesce rapid flaps: single-flight flush.
                mutex.withLock {
                    runCatching { backup.flushPendingSync() }
                        .onSuccess { Log.i(TAG, "network available — pending flush attempted") }
                        .onFailure { Log.w(TAG, "network flush pending: ${it.message}") }
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                onAvailable(network)
            }
        }
    }

    fun register() {
        if (registered) return
        val manager = cm ?: return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(req, callback)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "register failed: ${e.javaClass.simpleName}")
        }
    }

    fun unregister() {
        if (!registered) return
        registered = false
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "RiyDrive"
    }
}

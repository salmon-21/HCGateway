package dev.shuchir.hcgateway.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drops pooled connections whose network has gone away.
 *
 * Sockets established over a network that is no longer current stay in OkHttp's
 * pool looking reusable, but every request that picks one up fails — the symptom
 * being an app that reports the server unreachable until it's force-stopped
 * (which is just a heavy-handed eviction). Tailscale rebinding its underlying
 * network is the common trigger.
 *
 * This is process-scoped on purpose: background syncs run from WorkManager with
 * no UI alive, and they share the same pool.
 */
@Singleton
class StaleConnectionEvictor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    /**
     * The network our sockets were established over. Only a change of identity
     * strands the pool — capability updates on the same network do not.
     */
    private var boundNetwork: Network? = null

    fun start() {
        boundNetwork = connectivityManager.activeNetwork

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                rebind(network, "switched to $network")
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    rebind(connectivityManager.activeNetwork, "lost $network")
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun rebind(network: Network?, reason: String) {
        if (network == boundNetwork) return
        boundNetwork = network
        okHttpClient.connectionPool.evictAll()
        Timber.d("Evicted connection pool: $reason")
    }
}

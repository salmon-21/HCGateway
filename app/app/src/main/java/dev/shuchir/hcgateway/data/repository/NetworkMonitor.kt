package dev.shuchir.hcgateway.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    // Lazy so constructing the monitor doesn't drag in the whole OkHttp stack;
    // the client is only needed once a network actually changes.
    private val okHttpClient: Lazy<OkHttpClient>,
) {
    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Whether *any* network currently offers internet. Derived from the active
     * network rather than from the last callback, so losing one network while
     * another stays up doesn't read as offline — under a VPN the underlying
     * network churns and `onLost` fires for transports we aren't using.
     */
    private fun hasInternet(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /**
     * Drop every pooled connection. Sockets established over a network that has
     * since gone away stay in OkHttp's pool looking reusable, but every request
     * that picks one up fails — the symptom being an app that reports the server
     * unreachable until it's force-stopped (which is just a heavy-handed evict).
     * Tailscale rebinding its underlying network is the common trigger.
     */
    private fun evictConnections(reason: String) {
        runCatching { okHttpClient.get().connectionPool.evictAll() }
            .onSuccess { Timber.d("Evicted connection pool: $reason") }
            .onFailure { Timber.w(it, "Failed to evict connection pool: $reason") }
    }

    val isConnected: Flow<Boolean> = callbackFlow {
        // The network carrying our sockets. A change of identity — not merely a
        // capabilities update on the same network — is what strands the pool.
        var boundNetwork: Network? = connectivityManager.activeNetwork

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != boundNetwork) {
                    boundNetwork = network
                    evictConnections("switched to $network")
                }
                trySend(hasInternet())
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    boundNetwork = connectivityManager.activeNetwork
                    evictConnections("lost $network")
                }
                trySend(hasInternet())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(hasInternet())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(hasInternet())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
}

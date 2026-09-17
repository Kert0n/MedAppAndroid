package com.kert0n.medapp.platform.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.kert0n.medapp.feature.connectivity.Connection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Связь по словам системы: сеть по умолчанию и то, выходили ли через неё наружу (`VALIDATED`).
 *
 * Слушать систему начинает при создании — первым, кто спросил о связи, — и не перестаёт, пока жив
 * процесс. До первого ответа системы стоит то, что она говорит о сети по умолчанию в этот момент.
 */
@Singleton
class SystemConnection @Inject constructor(@ApplicationContext context: Context) : Connection {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val state = MutableStateFlow(connectivity.getNetworkCapabilities(connectivity.activeNetwork).reachesOut())

    override val online: StateFlow<Boolean> = state.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                state.value = capabilities.reachesOut()
            }

            override fun onLost(network: Network) {
                state.value = false
            }
        })
    }

    private fun NetworkCapabilities?.reachesOut(): Boolean =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

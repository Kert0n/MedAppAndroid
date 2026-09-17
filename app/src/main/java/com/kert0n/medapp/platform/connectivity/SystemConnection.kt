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
 * процесс. **Сперва подписка, потом снимок:** снимок до подписки не узнал бы о сети, пропавшей
 * между ними, — у подписки на сеть по умолчанию без сети нет ни одного вызова, и «связь есть»
 * простояло бы до следующего события. Снимок, снятый после подписки, не перетирает то, что
 * система уже успела сказать сама: её слово новее.
 */
@Singleton
class SystemConnection @Inject constructor(@ApplicationContext context: Context) : Connection {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val state = MutableStateFlow(false)

    /** Система сказала своё хоть раз — снимок больше не нужен. Под замком вместе с [state]. */
    private var heard = false

    override val online: StateFlow<Boolean> = state.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                hear(capabilities.reachesOut())
            }

            override fun onLost(network: Network) {
                hear(false)
            }
        })
        val snapshot = connectivity.getNetworkCapabilities(connectivity.activeNetwork).reachesOut()
        synchronized(this) {
            if (!heard) state.value = snapshot
        }
    }

    private fun hear(online: Boolean) = synchronized(this) {
        heard = true
        state.value = online
    }

    private fun NetworkCapabilities?.reachesOut(): Boolean =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

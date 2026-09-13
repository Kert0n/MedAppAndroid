package com.kert0n.medapp.platform.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kert0n.medapp.queue.Synchronization
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Поводы синхронизации, которые приходят от платформы, пока процесс жив (PLAN E4): человек вошёл в
 * приложение — и связь появилась после того, как её не было. Оба зовут один координатор, и
 * совпавшие поводы сливаются в один заход.
 *
 * Вход — главный повод: истинное состояние нужно тогда, когда человек на него смотрит, и оставлять
 * приложение в неизвестном состоянии до фонового захода нельзя.
 */
@Singleton
class SyncTriggers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val synchronization: Synchronization
) {

    private val started = AtomicBoolean(false)

    /** Один раз на процесс. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = synchronization.request()
        })
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Связь, которая была при регистрации, поводом не считается: вход в приложение уже позвал
        // заход. Повод — связь, появившаяся после потери.
        val offline = AtomicBoolean(connectivity.activeNetwork == null)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (offline.compareAndSet(true, false)) synchronization.request()
            }

            override fun onLost(network: Network) {
                offline.set(true)
            }
        })
    }
}

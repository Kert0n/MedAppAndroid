package com.kert0n.medapp.platform.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.feature.course.CourseUpkeep
import com.kert0n.medapp.queue.Synchronization
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Поводы синхронизации, которые приходят от платформы, пока процесс жив (PLAN E4): человек вошёл в
 * приложение — и связь появилась после того, как её не было. Оба зовут один координатор, и
 * совпавшие поводы сливаются в один заход. Вход в приложение ещё и приводит в порядок календарь
 * лечений — без сети.
 *
 * Вход — главный повод: истинное состояние нужно тогда, когда человек на него смотрит, и оставлять
 * приложение в неизвестном состоянии до фонового захода нельзя.
 */
@Singleton
class SyncTriggers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val synchronization: Synchronization,
    private val upkeep: CourseUpkeep,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val started = AtomicBoolean(false)

    /** Один раз на процесс. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Календарь сети не ждёт: неответ и окно пунктов приводятся в порядок сразу (PLAN F4).
                scope.launch { runCatching { upkeep.keepUp() } }
                synchronization.request()
            }
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

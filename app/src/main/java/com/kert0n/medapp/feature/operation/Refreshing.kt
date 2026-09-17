package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.queue.SnapshotApplier
import com.kert0n.medapp.queue.Synchronization
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Заход синхронизации для того, кто на него смотрит (PLAN H3 №28). Очередь экрану не видна вовсе
 * (`LayerBoundariesTest`), поэтому её ход переводится здесь в то, о чём говорят человеку: идёт ли
 * заход, когда последний раз лёг снимок и не потому ли он не лёг, что связи нет.
 *
 * Своей логики у этого нет: заход делает [Synchronization], и повторные поводы она объединяет сама.
 */
@Singleton
class Refreshing @Inject constructor(private val synchronization: Synchronization) {

    val state: Flow<Progress> = synchronization.state.map { it.asProgress() }

    /**
     * Обновить сейчас: заход целиком — очередь и снимок (PLAN E4). Сбой захода экран не роняет:
     * что не доехало, видно в самой очереди, и говорить об этом ещё и падением незачем. Отмену
     * `attempt` пропускает наружу — экран, с которого ушли, ждать захода не должен.
     */
    suspend fun now() {
        attempt { synchronization.synchronize() }
    }

    /**
     * Ход захода. [isOffline] — последнее чтение снимка не состоялось из-за связи: очередь при
     * этом цела, и говорить об ошибке было бы неправдой.
     */
    data class Progress(
        val isRunning: Boolean = false,
        val refreshedAt: Instant? = null,
        val isOffline: Boolean = false
    )

    private fun Synchronization.State.asProgress(): Progress {
        val snapshot = lastRound?.snapshot
        return Progress(
            isRunning = running,
            refreshedAt = refreshedAt,
            isOffline = snapshot is SnapshotApplier.Outcome.Refused &&
                snapshot.reason == Unavailability.NO_CONNECTION
        )
    }
}

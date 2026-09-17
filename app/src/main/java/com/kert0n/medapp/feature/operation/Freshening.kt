package com.kert0n.medapp.feature.operation

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.queue.Rereading
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Человек открыл общую вещь — она перечитывается, прежде чем он по ней решит (PLAN E4). Экран
 * ждёт возврата отсюда и показывает загрузку; что прочитано, приходит в него из базы само, поэтому
 * исхода дверь не возвращает: после неё экран делает одно и то же, как бы чтение ни кончилось.
 *
 * Спрашивается не всё. **Без связи** — ничего: ждать некого, и человек видит базу сразу. **Вещь,
 * которой сервер не знает**, — местная полка, коробка, ещё не доехавшая до сервера, — тоже нет:
 * её `GET` ответил бы 404, и вещь пропала бы на ровном месте. Отказ чтения человеку не называется:
 * о перечитывании он не просил, а что не доехало, говорит экран №28.
 */
@Singleton
class Freshening @Inject constructor(
    private val rereading: Rereading,
    private val connection: Connection,
    private val medKits: MedKitStorageRepository,
    private val packages: PackageStorageRepository,
    private val transactions: Transactions
) {

    /** Полка и её содержимое. */
    suspend fun medKit(medKitId: Uuid) {
        if (!connection.online.value) return
        if (transactions.run { medKits.find(medKitId)?.answersToServer } != true) return
        attempt { rereading.medKit(medKitId) }
    }

    /** Одна коробка. */
    suspend fun pack(packageId: Uuid) {
        if (!connection.online.value) return
        if (!transactions.run { packages.answersToServer(packageId) }) return
        attempt { rereading.pack(packageId) }
    }

    /** Несколько коробок разом — источники одного лечения: ждут все вместе, а не по очереди. */
    suspend fun packs(packageIds: Set<Uuid>) = coroutineScope {
        packageIds.map { async { pack(it) } }.awaitAll()
        Unit
    }
}

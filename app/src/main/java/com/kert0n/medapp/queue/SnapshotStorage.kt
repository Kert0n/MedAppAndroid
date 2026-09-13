package com.kert0n.medapp.queue

import com.kert0n.medapp.network.pack.PackageSnapshot
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Что чтению полного снимка нужно от хранилища — и только это (PLAN E4). Снимок утверждает не
 * «вот что изменилось», а «вот всё, что мне доступно», поэтому кладётся он целиком и одной
 * транзакцией: половина правды противоречила бы другой половине.
 *
 * Транзакция принадлежит хранилищу, как и у очереди: сеть никогда не выполняется внутри неё (F5).
 */
interface SnapshotStorage {

    /**
     * Снимок в базу: число участников у каждой названной полки и серверная часть каждой её
     * коробки. Что из этого ляжет, решают версии — запоздалая половина не откатывает свежую
     * (PLAN E1), — и решает это одна дверь `PackageDao.applySnapshot`.
     *
     * [at] — момент наблюдения: им датируется первое знакомство с чужой коробкой.
     */
    suspend fun lay(participants: Map<Uuid, Long>, packages: List<PackageSnapshot>, at: Instant)
}

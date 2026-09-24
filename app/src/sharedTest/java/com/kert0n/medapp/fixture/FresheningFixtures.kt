package com.kert0n.medapp.fixture

import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.pack.PackageSnapshot
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Перечитывание при связи против [server]: чтение живёт в своей области, как в приложении, и экран
 * ждёт его по-настоящему.
 */
fun onlineFreshening(
    server: RereadingServer,
    clock: Clock = Clock.systemUTC(),
    knows: Collection<PackageSnapshot> = emptyList(),
    queue: QueueService = QueueService(DirectTransactions, FakeQueue(knows))
): Freshening = Freshening(
    server.rereading,
    FakeConnection(online = true),
    queue,
    DirectTransactions,
    clock,
    CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
)

/**
 * Перечитывание без связи: сервер не спрашивается, экран не ждёт. Для проверок, которым
 * перечитывание не предмет, — они видят экран таким, каким он был до него (PLAN E4).
 */
fun offlineFreshening(
    clock: Clock = Clock.systemUTC()
): Freshening = Freshening(
    RereadingServer(clock).rereading,
    FakeConnection(online = false),
    QueueService(DirectTransactions, FakeQueue()),
    DirectTransactions,
    clock,
    CoroutineScope(Dispatchers.Unconfined)
)

/** Коробка, которую сервер уже создал: у неё есть серверная версия. Данные, а не правило. */
fun onServer(pkg: com.kert0n.medapp.domain.pack.Package): PackageSnapshot =
    PackageSnapshot(pkg, com.kert0n.medapp.queue.pack.PackageSyncState(pkg.id, com.kert0n.medapp.queue.ResourceVersion(1)))

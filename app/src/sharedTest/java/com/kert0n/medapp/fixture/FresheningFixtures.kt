package com.kert0n.medapp.fixture

import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.storage.medkit.MedKitStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Перечитывание при связи против [server]: чтение живёт в своей области, как в приложении, и экран
 * ждёт его по-настоящему.
 */
fun onlineFreshening(
    server: RereadingServer,
    medKits: MedKitStorageRepository,
    packages: PackageStorageRepository,
    clock: Clock = Clock.systemUTC()
): Freshening = Freshening(
    server.rereading,
    FakeConnection(online = true),
    medKits,
    packages,
    DirectTransactions,
    clock,
    CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
)

/**
 * Перечитывание без связи: сервер не спрашивается, экран не ждёт. Для проверок, которым
 * перечитывание не предмет, — они видят экран таким, каким он был до него (PLAN E4).
 */
fun offlineFreshening(
    medKits: MedKitStorageRepository,
    packages: PackageStorageRepository,
    clock: Clock = Clock.systemUTC()
): Freshening = Freshening(
    RereadingServer(clock).rereading,
    FakeConnection(online = false),
    medKits,
    packages,
    DirectTransactions,
    clock,
    CoroutineScope(Dispatchers.Unconfined)
)

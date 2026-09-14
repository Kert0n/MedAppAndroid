package com.kert0n.medapp.queue.pack

import com.kert0n.medapp.domain.course.Course

/**
 * Брони после изменения выделений — разницей, одной функцией на всех, кто выделения меняет:
 * зажим под чужое изменение, счёт доз мимо плана, укладка снимка (PLAN D5, E2). По каждой пачке
 * обоих составов сравнивается выделение до и после: изменилось — `SetClaim` на новое
 * (выделено × доза); ноль, отключение или отвязка — `ReleaseClaim`; неизменное не едет. Ставит
 * команды тот, кто владеет транзакцией.
 */
fun Course.claimChangesSince(before: Course): List<PackageSyncCommand> =
    (before.sources.map { it.pkg } + sources.map { it.pkg }).distinct().mapNotNull { pkg ->
        // Броней нет и у отвязанной, и у пачки с нулём: снимать одинаково нечего.
        val was = before.allocatedOf(pkg)?.takeUnless { it.isZero }
        val now = allocatedOf(pkg)?.takeUnless { it.isZero }
        when {
            was == now -> null
            now == null -> PackageSyncCommand.ReleaseClaim(pkg.id)
            else -> PackageSyncCommand.SetClaim(pkg.id, now)
        }
    }

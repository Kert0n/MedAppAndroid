package com.kert0n.medapp.feature.packages

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.Laying
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек выбросил коробку (ТЗ 4.1.1.3.5). Коробки больше нет, а история — приёмы — держится за
 * запись о ней и остаётся (PLAN D3, D6).
 *
 * **Решение и подтверждение — разные моменты, и разъезжаются они по границе публикации.** Своя
 * полка существует только у нас: решение и есть подтверждение, коробка кончается сразу. Общую
 * полку видят другие люди, и выбросить с неё молча нельзя — сосед мог отложить коробку себе. Туда
 * уходит команда `Delete` со своим предусловием, а до ответа коробка **цела и видна**: человеку
 * показано, что она помечена (PLAN E1). Конец ей приносит подтверждение сервера, и тогда же
 * лечение теряет её источником. Пометка — статус `REMOVING` на самой коробке: пользоваться ею
 * до ответа нельзя, а отказ полки снимает пометку, и человек решает заново.
 */
class PackageRemoval @Inject constructor(
    private val packages: PackageRecords,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun remove(packageId: Uuid): Outcome = transactions.run {
        val pkg = packages.find(packageId) ?: return@run Outcome.GONE
        if (!pkg.status.allowsUse) return@run Outcome.UNUSABLE
        val now = clock.instant()
        when (val laying = queue.removal(pkg)) {
            is Laying.Awaiting -> {
                queue.change(pkg.medKit, laying.errands, now) { packages.mark(pkg.id, PackageStatus.REMOVING, by = laying.by) }
                Outcome.MARKED
            }
            else -> {
                discard(pkg, now)
                Outcome.REMOVED
            }
        }
    }

    /** Шаг внутри чужой транзакции — коробка уходит по решению человека, следа не остаётся (D7). */
    internal suspend fun discard(pkg: Package, at: Instant) {
        packages.end(pkg.ended(), at).readThisTransaction("пачка")
    }

    /**
     * Чем кончилось. Убрали — экран уходит с карточки; пометили — карточка остаётся и говорит, что
     * коробка ждёт ответа полки; коробки и так нет — закрывает молча; коробка уже ждёт другого
     * решения — удаления или выхода — и трогать её нельзя (PLAN E1).
     */
    enum class Outcome { REMOVED, MARKED, GONE, UNUSABLE }
}

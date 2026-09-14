package com.kert0n.medapp.feature.packages

import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.feature.course.CourseFollowing
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.pack.PackageAdjustment
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек пересчитал коробку или выбросил часть (ТЗ 4.1.1.3.4; PLAN D3, E1). Число меняется,
 * дошедшее до нуля кончает коробку; истории у коробки нет (D7).
 *
 * **Своя полка**: число человека и есть истина — переход к коробке, прочитанной той же
 * транзакцией (F5), а кончившаяся уходит своей дверью и лечение теряет её источником.
 * **Полка, отвечающая серверу**: подтверждённое число трогать нельзя — истина по количеству там
 * (E1), — поэтому уезжает `CorrectStock(seen, actual)`: разница относительно увиденного, которую
 * очередь кладёт поверх свежего серверного числа сама (C1). Коробка до ответа помечена
 * `CHANGING`; ноль в проекции — коробка кончится, когда полка согласится. Утилизация на такой полке
 * — тот же пересчёт: видел [seen], выбросил [amount], осталось `seen − amount`.
 *
 * Лечение, державшее коробку, зажимается под новую доступность ([CourseFollowing], D5).
 */
class PackageAdjusting @Inject constructor(
    private val packages: PackageStorageRepository,
    private val following: CourseFollowing,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun adjust(packageId: Uuid, action: Action): Outcome = transactions.run {
        val pkg = packages.find(packageId) ?: return@run Outcome.GONE
        if (!pkg.status.allowsUse) return@run Outcome.UNUSABLE
        val now = clock.instant()
        val ended = if (packages.answersToServer(packageId)) announce(pkg, action, now) else apply(pkg, action, now)
        // Кончившуюся коробку лечение уже потеряло своей дверью; кончающуюся на полке потеряет
        // ответ. Зажимать есть что только у оставшейся — и по тому же числу, что на экране (D4).
        val after = if (ended) null else packages.projection(pkg.id)?.availability
        if (after != null && !after.effective.isZero) following.follow(pkg.id, now)
        if (ended) Outcome.ENDED else Outcome.ADJUSTED
    }

    /** Своя полка: переход и запись; `true` — коробка кончилась. */
    private suspend fun apply(pkg: Package, action: Action, now: Instant): Boolean {
        val adjustment = when (action) {
            is Action.Recount -> PackageAdjustment.Recount(pkg.id, action.actual)
            is Action.Dispose -> PackageAdjustment.Disposal(pkg.id, action.amount)
        }
        val ended = adjustment.applyTo(pkg) is PackageAfter.Ended
        check(packages.adjust(adjustment, at = now)) { "пачка прочитана этой же транзакцией" }
        return ended
    }

    /**
     * Полка, отвечающая серверу: команда-разница и пометка. Коробка при этом остаётся: ноль в
     * проекции значит «кончится, когда полка согласится», а строка живёт до ответа (PLAN E1).
     */
    private suspend fun announce(pkg: Package, action: Action, now: Instant): Boolean {
        val command = when (action) {
            is Action.Recount -> PackageSyncCommand.CorrectStock(pkg.id, seen = action.seen, actual = action.actual)
            is Action.Dispose -> PackageSyncCommand.CorrectStock(pkg.id, seen = action.seen, actual = action.seen.minusOrZero(action.amount))
        }
        val announced = QueuedCommand(Uuid.random(), command)
        queue.change(pkg.medKit, listOf(announced), now) {
            check(packages.mark(pkg.id, PackageStatus.CHANGING, by = announced.id)) { "пачка прочитана этой же транзакцией" }
            true
        }
        // Коробка кончится, когда полка согласится: до ответа строка живёт, и терять её нечем.
        return false
    }

    /**
     * Что человек сделал с коробкой. [Recount.seen] и [Dispose.seen] — число, которое он видел на
     * экране: на полке, отвечающей серверу, от него отсчитывается разница (C1); на своей полке
     * истина — названное число, и увиденное ни на что не влияет.
     */
    sealed interface Action {
        data class Recount(val seen: Quantity, val actual: Quantity) : Action
        data class Dispose(val seen: Quantity, val amount: Quantity) : Action
    }

    /**
     * Чем кончилось. Число изменилось — экран показывает его (на общей полке — проекцией, с
     * пометкой); коробка кончилась — экран уходит с карточки; коробки нет — уходит молча; коробка
     * ждёт удаления или выхода — трогать её нельзя (PLAN E1).
     */
    enum class Outcome { ADJUSTED, ENDED, GONE, UNUSABLE }
}

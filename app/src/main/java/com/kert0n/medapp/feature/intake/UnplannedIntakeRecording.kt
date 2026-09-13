package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.feature.course.CourseClamping
import com.kert0n.medapp.feature.course.openPlan
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек принял что-то мимо плана (ТЗ 4.1.1.11.6; PLAN D6): факт и расход из любой коробки.
 * Акт — `Package.take`, те же отказы, что у пункта курса; на своей полке ещё и «в коробке меньше,
 * чем принято»: местный остаток списать не из чего, у общей истина — сервер (E3).
 *
 * **Заденет занятое** — принято больше, чем свободно любому (D4: доступное мне без моего
 * выделения, посчитанное от числа на экране). Такой приём сценарий не записывает, а отвечает этим и называет свободное; после
 * подтверждения человеком ([touchingReservedConfirmed]) факт записан, расход — местно либо
 * `Consume` без брони, а мой курс, державший коробку, зажат под оставшееся ([CourseClamping]);
 * соседям нехватку приносит снимок (C1 «Разовый приём из занятого»). Кончившуюся коробку курс
 * теряет её концом — там, где записан приём.
 */
class UnplannedIntakeRecording @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val courses: CourseStorageRepository,
    private val packages: PackageStorageRepository,
    private val clamping: CourseClamping,
    private val queue: QueueService,
    private val transactions: Transactions,
    private val clock: Clock
) {

    suspend fun record(
        packageId: Uuid,
        amount: Dose,
        at: Instant,
        touchingReservedConfirmed: Boolean = false
    ): Outcome = transactions.run {
        val pkg = packages.find(packageId) ?: return@run Outcome.Rejected(IntakeRejected.Reason.PACKAGE_UNUSABLE)
        val taken = pkg.take(amount, at).getOrElse { return@run Outcome.Rejected((it as IntakeRejected).reason) }
        val spendsLocally = !packages.answersToServer(packageId)
        if (spendsLocally && !pkg.quantity.covers(amount)) return@run Outcome.Rejected(IntakeRejected.Reason.INSUFFICIENT)
        // Занятое — моё выделение и чужие брони, посчитанные от того же числа, которое человек
        // видит на экране: решает он по нему (PLAN D4).
        val seen = checkNotNull(packages.projection(pkg.id)) { "пачка прочитана этой же транзакцией" }.availability
        val free = seen.freeForAnyone
        if (!free.covers(amount) && !touchingReservedConfirmed) return@run Outcome.TouchesReserved(free)

        val now = clock.instant()
        val intake = UnplannedIntake(Uuid.random(), taken)
        val consume = QueuedCommand(Uuid.random(), PackageSyncCommand.Consume(pkg.id, amount, intake.id, claimAfter = null))
        val sync = if (spendsLocally) {
            IntakeSyncState(intake.id, IntakeAccounting.LOCAL_APPLIED)
        } else {
            IntakeSyncState(intake.id, IntakeAccounting.PENDING, consume.id)
        }
        val outcome = IntakeOutcome(intake, expected = emptySet(), sync = sync, recordedAt = now)
        // Местному расходу везти нечего: сервер о коробке не знает — расскажет о ней её создание (E6).
        val commands = if (spendsLocally) emptyList() else listOf(consume)
        val recorded = queue.change(pkg.medKit, commands, now) { intakes.record(outcome) }
        check(recorded) { "пачка прочитана этой же транзакцией" }

        // Что осталось — то же, что увидит человек: на своей полке расход уже списан, на общей он
        // лежит в проекции командой. Ноль — коробка кончилась или кончится по ответу, и её теряет
        // дверь конца (PLAN D4, E1).
        val after = packages.projection(pkg.id)?.availability
        if (after != null && !after.effective.isZero) clamping.clampTheCourseHolding(pkg, after, now)
        Outcome.Recorded(intake.projection(), sync.accounting)
    }

    /**
     * Чем кончилось. Записано — экран показывает факт и где его расход; заденет занятое — экран
     * спрашивает подтверждения, называя свободное, и ничего не записано; отказ — причина по месту.
     */
    sealed interface Outcome {
        data class Recorded(val intake: IntakeProjection.Unplanned, val accounting: IntakeAccounting) : Outcome
        data class TouchesReserved(val free: Quantity) : Outcome
        data class Rejected(val reason: IntakeRejected.Reason) : Outcome
    }
}

package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.feature.course.CourseClosing
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Целое действие «принял» по пункту курса: факт, остаток, прогресс, обеспечение пачки, конец
 * эпизода и доставка согласуются одной транзакцией и по тому, что лежит в базе, а не по тому, что
 * экран прочитал раньше (PLAN D5, D6, F5). Своя аптечка списывает локально, общая ставит расход
 * командой — её заберёт outbox после коммита, а человек её не ждёт: подтверждение записано, и от
 * сети оно не зависит (PLAN E4). Домен считает от факта — подтверждённого остатка и чужих
 * броней; незакрытые команды очереди — доставка, и о ней он не думает (PLAN D4).
 */
class IntakeConfirmation @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val courses: CourseStorageRepository,
    private val packages: PackageStorageRepository,
    private val transactions: Transactions,
    private val queue: QueueService,
    private val closing: CourseClosing,
    private val clock: Clock
) {

    /**
     * Принято [amount] из пачки [packageId] в момент [at], который называет человек: сейчас или
     * вчера — проверка одна и та же. Отказ — [IntakeRejected] внутри `Result`, и тогда не записано
     * ничего. Повтор по уже принятому пункту ничего не меняет и отвечает тем, что записано.
     */
    suspend fun confirm(intakeId: Uuid, packageId: Uuid, amount: Dose, at: Instant): Result<Confirmed> =
        transactions.run { write(intakeId, packageId, amount, at) }

    private suspend fun write(intakeId: Uuid, packageId: Uuid, amount: Dose, at: Instant): Result<Confirmed> {
        val now = clock.instant()
        val intake = requireNotNull(intakes.find(intakeId) as? CourseIntake) { "подтверждается пункт курса" }
        val record = checkNotNull(courses.findRecord(intake.courseId)) { "у пункта курса есть запись эпизода" }
        if (intake.status == IntakeStatus.TAKEN) {
            val sync = checkNotNull(intakes.syncStateOf(intake.id)) { "принятый пункт записан" }
            return Result.success(Confirmed(intake.projection(), sync.accounting, episodeClosed = !record.isOpen))
        }
        if (!record.isOpen) return rejected(IntakeRejected.Reason.EPISODE_CLOSED)
        val course = checkNotNull(courses.findPlan(intake.courseId)) { "у идущего эпизода есть план" }
        val pkg = packages.find(packageId) ?: return rejected(IntakeRejected.Reason.PACKAGE_UNUSABLE)
        if (amount.unit != intake.unit) return rejected(IntakeRejected.Reason.UNIT_MISMATCH)
        // Пункт курса принимают из пачки курса; из любой другой это внеплановый факт, и пункт им
        // не закрывается (PLAN D5). Отказ — до `take`: не записано ничего.
        if (!course.isSource(pkg.ref)) return rejected(IntakeRejected.Reason.PACKAGE_NOT_A_SOURCE)
        val confirmed = intake.confirm(pkg.take(amount, at).getOrElse { return Result.failure(it) })

        val others = intakes.ofCourse(course.id).filterIsInstance<CourseIntake>().filter { it != intake }
        val progress = CourseProgress(
            taken = others.filter { it.status == IntakeStatus.TAKEN }.mapTo(HashSet()) { it.slot } + confirmed.slot,
            missed = others.filter { it.status == IntakeStatus.MISSED }.mapTo(HashSet()) { it.slot }
        )
        val completion = CourseCompletion(course, progress)
        val finished = completion.reached

        // Выделение пачки после приёма и бронь, которая уезжает вместе с расходом (PLAN D5, E2).
        // Местную коробку расход опустошает здесь же, и кончившаяся коробка источником не бывает:
        // курс теряет её тем же решением, что записывает приём (D3). У общей истина — сервер.
        val spendsLocally = !pkg.medKit.answersToServer
        val emptied = spendsLocally && pkg.consume(amount) is PackageAfter.Ended
        val allocated = course.sources.firstOrNull { it.pkg == pkg.ref }?.allocatedDoses
        val reallocation = when {
            allocated == null || finished -> null
            // Кончившуюся коробку курс теряет её же концом — одним переходом внутри записи приёма
            // (PLAN D3, D5). Второй раз отвязывать нечего, и считать по ней обеспечение не из чего.
            emptied -> null
            else -> {
                val availableAfter = PackageAvailability(pkg, effective = pkg.quantity).availableToMe.minusOrZero(amount.quantity)
                val doses = course.dosesAfterIntake(pkg.ref, amount, availableAfter)
                if (doses == allocated) null else CourseReallocation(course.allocate(pkg.ref, doses, now), course.revision)
            }
        }
        val claimAfter = when {
            allocated == null -> null
            finished || emptied -> Quantity.zero(amount.unit)
            else -> (reallocation?.course ?: course).allocatedOf(pkg.ref)
        }

        val consume = QueuedCommand(Uuid.random(), PackageSyncCommand.Consume(pkg.id, amount, intake.id, claimAfter))
        val release = QueuedCommand(Uuid.random(), PackageSyncCommand.ReleaseClaim(pkg.id), dependsOn = setOf(consume.id))
            .takeIf { claimAfter?.isZero == true }
        val sync = if (spendsLocally) {
            IntakeSyncState(intake.id, IntakeAccounting.LOCAL_APPLIED)
        } else {
            IntakeSyncState(intake.id, IntakeAccounting.PENDING, consume.id)
        }
        val outcome = IntakeOutcome(confirmed, setOf(IntakeStatus.PLANNED, IntakeStatus.MISSED), sync, reallocation, recordedAt = now)
        val recorded = queue.change(pkg.medKit, listOfNotNull(consume, release), now) { intakes.record(outcome) }
        check(recorded) { "пункт и пачка прочитаны этой же транзакцией" }

        if (finished) {
            // Снятие брони с этой пачки уже уехало зависимым от расхода — второй раз не ставится.
            closing.close(course, completion.close(record, intakes.ofCourse(course.id).filterIsInstance<CourseIntake>(), now), now, except = pkg.ref)
        } else {
            intakes.prunePlanned(course.id, course.remainingOccurrences(progress).toSet())
        }
        return Result.success(Confirmed(confirmed.projection(), sync.accounting, episodeClosed = finished))
    }

    private fun rejected(reason: IntakeRejected.Reason): Result<Confirmed> = Result.failure(IntakeRejected(reason))

    /**
     * Что стало после подтверждения: принятый пункт **проекцией** — сущность действительна лишь
     * в транзакции, которая её прочитала, и наружу не уходит (PLAN H1), — где его расход, в
     * локальном остатке или в очереди, и закончилось ли им лечение.
     */
    data class Confirmed(
        val intake: IntakeProjection.Scheduled,
        val accounting: IntakeAccounting,
        val episodeClosed: Boolean
    )
}

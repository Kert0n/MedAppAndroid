package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.feature.course.CourseCalendar
import com.kert0n.medapp.feature.course.CourseClosing
import com.kert0n.medapp.feature.course.openPlan
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
import com.kert0n.medapp.queue.readThisTransaction
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import com.kert0n.medapp.storage.intake.IntakeOutcome
import com.kert0n.medapp.storage.intake.IntakeStorageRepository
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.feature.notification.ReminderWithdrawal
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
 *
 * Вопрос перед записью — третий исход рядом с записью и отказом: просроченную коробку сценарий
 * не списывает молча, а спрашивает, и пишет только с подтверждением (PLAN D6, ТЗ 4.1.1.5.5).
 */
class IntakeConfirmation @Inject constructor(
    private val intakes: IntakeStorageRepository,
    private val courses: CourseStorageRepository,
    private val packages: PackageStorageRepository,
    private val transactions: Transactions,
    private val queue: QueueService,
    private val closing: CourseClosing,
    private val calendar: CourseCalendar,
    private val reminders: ReminderWithdrawal,
    private val clock: Clock
) {

    /**
     * Принято [amount] из пачки [packageId] в момент [at], который называет человек: сейчас или
     * вчера — проверка одна и та же. Отказ — [Outcome.Rejected], и тогда не записано ничего;
     * вопрос — [Outcome.Warned], тоже без записи, пока человек не ответит [acknowledged]; пункта
     * уже нет — [Outcome.Gone]. Повтор по уже принятому пункту ничего не меняет и отвечает тем,
     * что записано.
     */
    suspend fun confirm(
        intakeId: Uuid,
        packageId: Uuid,
        amount: Dose,
        at: Instant,
        acknowledged: Boolean = false
    ): Outcome = transactions.run { write(intakeId, packageId, amount, at, acknowledged) }

    private suspend fun write(intakeId: Uuid, packageId: Uuid, amount: Dose, at: Instant, acknowledged: Boolean): Outcome {
        val now = clock.instant()
        // Идентификатор пришёл снаружи — с экрана или из шторки: пропавший пункт — исход, не падение.
        val intake = intakes.find(intakeId) as? CourseIntake ?: return Outcome.Gone
        val record = checkNotNull(courses.findRecord(intake.courseId)) { "у пункта курса есть запись эпизода" }
        if (intake.status == IntakeStatus.TAKEN) {
            val sync = checkNotNull(intakes.syncStateOf(intake.id)) { "принятый пункт записан" }
            return Outcome.Confirmed(intake.projection(), sync.accounting, episodeClosed = !record.isOpen)
        }
        if (!record.isOpen) return rejected(IntakeRejected.Reason.EPISODE_CLOSED)
        val course = courses.openPlan(intake.courseId)
        val pkg = packages.find(packageId) ?: return rejected(IntakeRejected.Reason.PACKAGE_UNUSABLE)
        if (amount.unit != intake.unit) return rejected(IntakeRejected.Reason.UNIT_MISMATCH)
        // Акт по пачке — первым: он сверяет единицу коробки, а сравнивать числа разных единиц
        // нечем. Единица источника, проверенная при подключении, могла прийти другой снимком.
        val taken = pkg.take(amount, at).getOrElse { return rejected((it as IntakeRejected).reason) }
        // Кому отвечает эта коробка: серверу — только когда он её знает, иначе расход местный и
        // команды не ставит, а расскажет о нём её же создание (PLAN E6).
        val spendsLocally = !packages.answersToServer(packageId)
        // Своя коробка списывается здесь же, и списать больше, чем в ней есть, нечем; у общей
        // истина по количеству — сервер, и нехватку отвечает он (PLAN E3).
        if (spendsLocally && !pkg.quantity.covers(amount)) {
            return rejected(IntakeRejected.Reason.INSUFFICIENT)
        }
        // Пункт курса принимают из пачки курса; из любой другой это внеплановый факт, и пункт им
        // не закрывается (PLAN D5).
        if (!course.isSource(pkg.ref)) return rejected(IntakeRejected.Reason.PACKAGE_NOT_A_SOURCE)
        // Вопросы — после отказов и до записи: на день приёма, названный человеком (PLAN D6).
        val warnings = listOfNotNull(
            pkg.facts.expiresOn?.takeIf { pkg.isExpiredOn(at.atZone(clock.zone).toLocalDate()) }?.let { IntakeWarning.Expired(it) }
        )
        if (warnings.isNotEmpty() && !acknowledged) return Outcome.Warned(warnings)
        // Прошлое до ответа, но после всех отказов — отказ не пишет ничего: неответ, чей день
        // кончился, — пропуск. Иначе конец лечения этим приёмом отменил бы такие пункты, а
        // отменённый пропуском уже не станет (PLAN D6).
        calendar.missOverdue(course, now)
        val confirmed = intake.confirm(taken)

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
        val emptied = spendsLocally && pkg.consume(amount) is PackageAfter.Ended
        val allocated = course.sources.firstOrNull { it.pkg == pkg.ref }?.allocatedDoses
        val reallocation = when {
            allocated == null || finished -> null
            // Кончившуюся коробку курс теряет её же концом — одним переходом внутри записи приёма
            // (PLAN D3, D5). Второй раз отвязывать нечего, и считать по ней обеспечение не из чего.
            emptied -> null
            else -> {
                // От того же числа, что на экране: незакрытые решения по коробке в нём уже есть,
                // и чужие брони из него вычтены (PLAN D4).
                val seen = packages.projection(pkg.id).readThisTransaction("пачка").availability
                val availableAfter = seen.availableToMe.minusOrZero(amount.quantity)
                val doses = course.dosesAfterIntake(pkg.ref, amount, availableAfter)
                // Пачка — источник, из которого принимают (проверено выше), и выделение ей законно.
                if (doses == allocated) null else CourseReallocation(course.allocate(pkg.ref, doses, now).getOrThrow(), course.revision)
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
        // Местному расходу везти нечего: сервер о коробке не знает — расскажет о ней её создание (E6).
        val commands = if (spendsLocally) emptyList() else listOfNotNull(consume, release)
        val recorded = queue.change(pkg.medKit, commands, now) { intakes.record(outcome) }
        recorded.readThisTransaction("пункт и пачка")

        // Ответ дан — напоминать больше нечего. Той же транзакцией: откат уносит отзыв вместе с
        // приёмом, а гасит карточку владелец доставки уже после коммита (PLAN D8, F5).
        reminders.withdraw(intakeId)
        if (finished) {
            // Снятие брони с этой пачки уже уехало зависимым от расхода — второй раз не ставится.
            closing.close(course, completion.close(record, intakes.ofCourse(course.id).filterIsInstance<CourseIntake>(), now), now, except = pkg.ref)
        } else {
            calendar.prune(course, course.remainingOccurrences(progress).toSet(), now)
        }
        return Outcome.Confirmed(confirmed.projection(), sync.accounting, episodeClosed = finished)
    }

    private fun rejected(reason: IntakeRejected.Reason): Outcome = Outcome.Rejected(reason)

    /**
     * Чем кончилось — три исхода, которые экран делает по-разному (PLAN D6). Записано — принятый
     * пункт **проекцией** (сущность действительна лишь в транзакции, которая её прочитала, и
     * наружу не уходит — PLAN H1), где его расход, в локальном остатке или в очереди, и
     * закончилось ли им лечение. Вопрос — ничего не записано, человек отвечает и повторяет вызов
     * с подтверждением. Отказ — причина по месту, подтверждением не снимается.
     */
    sealed interface Outcome {
        data class Confirmed(
            val intake: IntakeProjection.Scheduled,
            val accounting: IntakeAccounting,
            val episodeClosed: Boolean
        ) : Outcome

        data class Warned(val warnings: List<IntakeWarning>) : Outcome

        data class Rejected(val reason: IntakeRejected.Reason) : Outcome

        /**
         * Пункта, названного снаружи, уже нет: расписание перестроили, пока экран или шторка его
         * показывали. Записано ничего; экран закрывается молча (PLAN D6).
         */
        data object Gone : Outcome
    }
}

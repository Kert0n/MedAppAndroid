package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.intake.IntakeRecords
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseStorageRepository
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек правит пачки идущего лечения — какие, в каком порядке и сколько доз из каждой — одним
 * решением, как видит его на экране источников (PLAN D5, F5). Это не изменение назначения: доза и
 * расписание те же, меняется то, чем лечение обеспечено.
 *
 * Одной транзакцией: состав собирается доменными переходами над планом, прочитанным здесь же;
 * подключаемая пачка — живая, пригодная и не занятая другим курсом; ни одной пачке не выделяется
 * больше, чем она даёт и чем оставляет потребность, — сверх этого с других пачек само ничего не
 * снимается (C1 «Ползунок»); назначения пачек следуют составу; будущие плановые пункты
 * перестраиваются под новый порядок расхода.
 *
 * Брони — разницей: команду получает только пачка общей полки, чья бронь изменилась. Между разными
 * пачками зависимостей нет — бронь у сервера своя на каждую (C1 «Порядок броней в группе»).
 */
class SourceEditing @Inject constructor(
    private val courses: CourseStorageRepository,
    private val intakes: IntakeRecords,
    private val packages: PackageRecords,
    private val calendar: CourseCalendar,
    private val following: CourseFollowing,
    private val transactions: Transactions,
    private val clock: Clock
) {

    /** [sources] — пачки в порядке расходования, у каждой её выделение; [expected] — редакция, которую видел экран. */
    suspend fun save(id: Uuid, expected: Revision, sources: List<Source>): Outcome = transactions.run {
        require(sources.map { it.packageId }.distinct().size == sources.size) { "пачка стоит в составе один раз" }
        val record = courses.findRecord(id) ?: return@run Outcome.Gone
        if (!record.isOpen) return@run Outcome.AlreadyFinished
        val before = courses.openPlan(id)
        if (before.revision != expected) return@run Outcome.Stale
        val now = clock.instant()
        val wanted = sources.map { it.packageId }.toSet()

        var course = before
        for (source in before.sources) {
            if (source.pkg.id !in wanted) course = course.detach(source.pkg, now)
        }
        for (source in sources) {
            val held = course.sources.firstOrNull { it.pkg.id == source.packageId }
            course = if (held == null) {
                val pkg = packages.find(source.packageId)?.takeIf { it.status.allowsUse }
                    ?: return@run Outcome.PackageUnusable(source.packageId)
                if (courses.courseHolding(pkg.id).let { it != null && it != id }) return@run Outcome.PackageTaken(pkg.id)
                course.attach(pkg, source.doses, now).getOrElse { failure ->
                    return@run Outcome.Rejected((failure as? CourseRejected ?: throw failure).reason)
                }
            } else if (held.allocatedDoses != source.doses) {
                course.allocate(held.pkg, source.doses, now).getOrElse { failure ->
                    return@run Outcome.Rejected((failure as? CourseRejected ?: throw failure).reason)
                }
            } else {
                course
            }
        }
        for ((target, source) in sources.withIndex()) {
            val current = course.sources.indexOfFirst { it.pkg.id == source.packageId }
            if (current != target) course = course.reorder(current, target, now)
        }
        if (course === before) return@run Outcome.Saved(before.projection())

        // Прошлое до правки: неответ, чей день кончился, — пропуск, иначе перестройка вернула бы его в план.
        calendar.missOverdue(before, now)
        val progress = CourseProgress.of(intakes.ofCourse(id).filterIsInstance<CourseIntake>())
        val required = course.remainingDoses(progress)
        val availability = packages.availabilityFor(course)
        // Предел нарушает прежде всего та пачка, которой прибавили: её человек и двигал.
        course.sources
            .sortedByDescending { source -> before.sources.none { it.pkg == source.pkg && it.allocatedDoses >= source.allocatedDoses } }
            .firstOrNull { it.allocatedDoses > course.maxDoses(it.pkg, required, availability) }
            ?.let { return@run Outcome.BeyondLimit(it.pkg.id, course.maxDoses(it.pkg, required, availability)) }

        if (!courses.updateSources(course, expected)) return@run Outcome.Stale
        calendar.replan(course, now)
        // Брони — разницей, тем же владельцем, что у зажима и укладки снимка (PLAN E2).
        following.announceClaims(before, course, now)
        Outcome.Saved(course.projection())
    }

    /** Пачка в составе и сколько доз из неё выделено. */
    data class Source(val packageId: Uuid, val doses: Doses)

    /**
     * Чем кончилось. Сохранили — экран показывает состав; пачке выделено больше предела — назвать её
     * и предел; пачка занята другим курсом или ею нельзя пользоваться — назвать её; пачка не того
     * назначения — причина; лечение закончено или его нет — закрыть; устарело — перечитать.
     */
    sealed interface Outcome {
        data class Saved(val course: CourseProjection) : Outcome
        data class BeyondLimit(val packageId: Uuid, val limit: Doses) : Outcome
        data class PackageTaken(val packageId: Uuid) : Outcome
        data class PackageUnusable(val packageId: Uuid) : Outcome
        data class Rejected(val reason: CourseRejected.Reason) : Outcome
        data object AlreadyFinished : Outcome
        data object Gone : Outcome
        data object Stale : Outcome
    }
}

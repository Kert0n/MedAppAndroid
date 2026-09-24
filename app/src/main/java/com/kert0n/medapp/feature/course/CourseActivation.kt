package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.queue.Transactions
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Лечение началось (PLAN D5, F5). Из черновика рождаются **двое** — план и запись эпизода, одной
 * транзакцией; пачки источников занимаются этим курсом (одна пачка — один активный курс, C1);
 * выделения зажимаются под то, что пачки дают, и становятся бронями; строится окно плановых
 * пунктов. Пачка для начала не нужна: лечение без лекарства на руках обеспечено «0 из N».
 *
 * Занятость пачки проверяется до записи и называется исходом; первичный ключ назначения остаётся
 * страховкой от гонки двух активаций, а не способом узнать ответ.
 *
 * Брони едут очередью, по команде на пачку общей полки, и при связи уезжают тем же проходом; у
 * местной полки команд нет вовсе (E1).
 */
class CourseActivation @Inject constructor(
    private val courses: CourseRecords,
    private val packages: PackageRecords,
    private val calendar: CourseCalendar,
    private val following: CourseFollowing,
    private val transactions: Transactions,
    private val clock: Clock
) {

    /** [expected] — редакция черновика, которую видел человек, нажимая «начать». */
    suspend fun activate(id: Uuid, expected: Revision): Outcome = transactions.run {
        val draft = courses.findDraft(id)
            ?: return@run if (courses.findPlan(id) != null) Outcome.AlreadyStarted else Outcome.Gone
        if (draft.revision != expected) return@run Outcome.Stale
        for (source in draft.sources) {
            val pkg = packages.find(source.pkg.id)?.takeIf { it.usable }
                ?: return@run Outcome.PackageUnusable(source.pkg.id)
            if (courses.courseHolding(pkg.id) != null) return@run Outcome.PackageTaken(pkg.id)
        }
        val now = clock.instant()
        val started = draft.activate(now).getOrElse { failure ->
            return@run Outcome.Rejected((failure as? CourseRejected ?: throw failure).reason)
        }
        // Выделение — намерение человека, но больше, чем пачка даёт, его не бывает (PLAN D5).
        val course = started.course.let { it.clamped(it.totalDoses, packages.availabilityFor(it), now) }
        courses.activate(CourseDraft.Activation(course, started.record))
        // Лечение начали сегодня позже первого времени — прошедшие пункты сразу пропуски, а не ждущие.
        calendar.catchUp(course, now)
        // Бронь — разницей от «ничего не выделено»: ставит её единственный владелец (PLAN D5, E2).
        following.announceClaims(course.unallocated(), course, now)
        Outcome.Started(course.projection())
    }

    /**
     * Чем кончилось. Началось — экран открывает курс; уже идёт — то же, повтор ничего не менял;
     * черновика нет — закрыть; устарел — перечитать; назначение неполное — причина по месту; пачка
     * занята другим курсом или ею нельзя пользоваться — назвать её.
     */
    sealed interface Outcome {
        data class Started(val course: CourseProjection) : Outcome
        data object AlreadyStarted : Outcome
        data object Gone : Outcome
        data object Stale : Outcome
        data class Rejected(val reason: CourseRejected.Reason) : Outcome
        data class PackageTaken(val packageId: Uuid) : Outcome
        data class PackageUnusable(val packageId: Uuid) : Outcome
    }
}

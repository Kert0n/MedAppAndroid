package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.course.PackageFollowing
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.feature.packages.PackageRecords
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.QueueService
import com.kert0n.medapp.queue.Transactions
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * **Курс следует за коробкой — одна дверь** для всех, кто коробку изменил (PLAN D5, E4): человек
 * пересчётом, правкой сведений или разовым приёмом, сосед расходом или бронью, пришедшими снимком
 * или ответом на команду. Единственный владелец этой реакции; хранение даёт транзакцию и зовёт
 * его портом [PackageFollowing] из укладки снимка и ответа, сценарии человека — прямо.
 *
 * Шаги, по порядку: прошлое раньше зажима — у назначенного курса вчерашний неответ становится
 * пропуском (F4); совместимость источника с назначением — другая форма или единица отключает его
 * с причиной, вернувшаяся снимает её; зажим выделений `Course.clamped` под **доступное мне** от того
 * же числа, которое человек видит на экране; запись условно по редакции; событие сокращения;
 * назначение по пригодности; брони разницей — каждая своей пачке и её полке. Расписание, доза и
 * даты не трогаются: нехватка меняет обеспечение, а не план (C1).
 *
 * Конец коробки — вторая дверь того же владельца ([lost]): лечение теряет источник её же
 * переходом, обеспечение упало — событие, брони — разницей; отсоединять источник расширением DAO
 * значило бы дать одному изменению два входа с разными следствиями.
 *
 * Читает и пишет одной транзакцией (F5); вложенность безопасна, и внутри транзакции того, кто
 * коробку изменил, следствие ложится вместе с причиной или не ложится вовсе.
 */
class CourseFollowing @Inject constructor(
    private val courses: CourseRecords,
    private val packages: PackageRecords,
    private val calendar: CourseCalendar,
    private val queue: QueueService,
    private val transactions: Transactions
) : PackageFollowing {

    override suspend fun follow(packageId: Uuid, at: Instant): Unit = transactions.run {
        // Кончившуюся коробку лечение теряет её же концом: зажимать по ней нечем.
        val ref = packages.find(packageId)?.ref ?: return@run
        courses.courseHolding(packageId)?.let { calendar.missOverdue(courses.openPlan(it), at) }
        for (courseId in courses.holdersOf(packageId)) {
            val draft = courses.findDraft(courseId)
            if (draft != null) {
                followAsADraft(draft, ref, at)
                continue
            }
            val plan = courses.planInProgress(courseId) ?: continue
            val course = plan.course
            // Совместимость — первой: отключённый источник в расклад не входит, и считать по нему нечего.
            // Вернувшийся источник занимает коробку — если её не держит другое лечение.
            val compatible = when (val fault = course.prescription.faultOf(ref)) {
                null -> courses.courseHolding(packageId).let { holder -> if (holder == null || holder == courseId) course.restoreSource(ref, at) else course }
                else -> course.faultSource(ref, fault, at)
            }
            val availability = packages.availabilityFor(compatible)
            val required = compatible.remainingDoses(plan.progress)
            val clamped = compatible.clamped(required, availability, at)
            if (clamped === course) continue
            courses.updateSources(clamped, course.revision).readThisTransaction("план")
            // Обеспеченных доз стало меньше — событие (PLAN D5). До — выделенное прежним курсом:
            // после каждого зажима выделение и есть обеспечение; после — обеспечение нового.
            val coveredBefore = minOf(course.allocatedDosesTotal, required)
            val coveredAfter = clamped.coverage(plan.progress, availability).coveredDoses
            if (coveredAfter < coveredBefore) {
                courses.recordReduction(CoverageReduction(Uuid.random(), courseId, packageId, coveredBefore, coveredAfter, at))
            }
            announceClaims(course, clamped, at)
        }
    }

    override suspend fun lost(pkg: PackageRef, at: Instant): Unit = transactions.run {
        courses.courseHolding(pkg.id)?.let { calendar.missOverdue(courses.openPlan(it), at) }
        for (courseId in courses.holdersOf(pkg.id)) {
            val draft = courses.findDraft(courseId)
            if (draft != null) {
                courses.saveDraft(draft.detach(pkg, at), draft.revision).readThisTransaction("черновик")
                continue
            }
            val plan = courses.planInProgress(courseId) ?: continue
            val course = plan.course
            val detached = course.detach(pkg, at)
            courses.updateSources(detached, course.revision).readThisTransaction("план")
            // Источника нет — обеспечение упало ровно на него: событие, как и у зажима (PLAN D5).
            val required = detached.remainingDoses(plan.progress)
            val coveredBefore = minOf(course.allocatedDosesTotal, required)
            val coveredAfter = detached.coverage(plan.progress, packages.availabilityFor(detached)).coveredDoses
            if (coveredAfter < coveredBefore) {
                courses.recordReduction(CoverageReduction(Uuid.random(), courseId, pkg.id, coveredBefore, coveredAfter, at))
            }
            // Бронь на пропавшую коробку снял её конец; остальным — разницей, если она есть.
            announceClaims(course, detached, at, except = pkg)
        }
    }

    /** Черновик за коробкой следует только совместимостью: выделений и броней у него нет (PLAN D5). */
    private suspend fun followAsADraft(draft: CourseDraft, ref: PackageRef, at: Instant) {
        val followed = when (val fault = draft.faultOf(ref)) {
            null -> draft.restoreSource(ref, at)
            else -> draft.faultSource(ref, fault, at)
        }
        if (followed === draft) return
        courses.saveDraft(followed, draft.revision).readThisTransaction("черновик")
    }

    /**
     * Бронь — `выделено × доза`: изменилось выделение — зажимом, счётом доз мимо плана, началом,
     * правкой или концом лечения — изменилась и она, и уезжает разницей по каждой пачке — на полку
     * **её** коробки, а не той, что изменилась (PLAN D5, E2). **Единственный**, кто ставит команды
     * брони (`ClaimOwnershipTest`): начало считает от `Course.unallocated()`, конец — к нему. Кому
     * везти, решает очередь: местной полке ничего, публикуемой — только объявления, а бронь
     * коробки, чьё создание ещё в пути, встаёт за ним в очередь полки. [except] — коробка, чьё
     * снятие уже уехало зависимым от расхода: второй раз его не ставят.
     */
    suspend fun announceClaims(before: Course, after: Course, at: Instant, except: PackageRef? = null) {
        for ((packageId, errands) in queue.claims(before, after, except?.id)) {
            val pkg = packages.find(packageId) ?: continue
            queue.change(pkg.medKit, errands, at) { true }
        }
    }
}

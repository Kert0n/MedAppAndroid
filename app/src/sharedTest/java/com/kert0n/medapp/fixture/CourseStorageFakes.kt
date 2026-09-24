package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.report.CourseInProgress
import com.kert0n.medapp.feature.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Хранение лечения в памяти для проверок экранов курсов: черновики, планы и записи лежат по
 * тождеству, записанное видно следующим чтением, редакция сверяется честно (PLAN F5).
 *
 * Что подделка **не** считает: обеспечение. Его считает база по прогрессу и раскладу пачек
 * (`CourseRoomRepositoryTest`), а экрану важно, что он показывает то, что ему отдали, — поэтому
 * обеспечение кладёт тест в [coverages]. Плановые пункты активации и закрытия здесь не
 * материализуются: календарь проверяется на себе.
 */
class FakeCourseStorage : CourseStorageRepository {

    val drafts = LinkedHashMap<Uuid, CourseDraft>()

    val plans = LinkedHashMap<Uuid, Course>()

    val records = LinkedHashMap<Uuid, CourseRecord>()

    /** Обеспечение по курсу — то, что положил тест: подделка его не считает. */
    val coverages = LinkedHashMap<Uuid, CourseCoverage>()

    val reductions = ArrayList<CoverageReduction>()

    private val changes = MutableStateFlow(0)

    fun holding(draft: CourseDraft): FakeCourseStorage = apply { drafts[draft.id] = draft; changed() }

    fun holding(course: Course, record: CourseRecord): FakeCourseStorage = apply {
        plans[course.id] = course
        records[record.id] = record
        changed()
    }

    fun holding(record: CourseRecord): FakeCourseStorage = apply { records[record.id] = record; changed() }

    fun covering(courseId: Uuid, coverage: CourseCoverage): FakeCourseStorage = apply {
        coverages[courseId] = coverage
        changed()
    }

    override fun observeDrafts(): Flow<List<CourseDraftProjection>> =
        changes.map { drafts.values.map { it.projection() } }

    override fun observePlan(id: Uuid): Flow<CourseProjection?> = changes.map { plans[id]?.projection() }

    override fun observeCoverage(id: Uuid): Flow<CourseCoverage?> = changes.map { coverages[id] }

    override fun observeCoverages(): Flow<Map<Uuid, CourseCoverage>> = changes.map { coverages.toMap() }

    override fun observeReductions(courseId: Uuid): Flow<List<CoverageReduction>> =
        changes.map { reductions.filter { it.courseId == courseId } }

    override suspend fun reductionsSince(courseId: Uuid, since: Instant): List<CoverageReduction> =
        reductions.filter { it.courseId == courseId && !it.at.isBefore(since) }

    override suspend fun recentReductions(since: Instant): List<CoverageReduction> =
        reductions.filter { !it.at.isBefore(since) }

    override suspend fun findDraft(id: Uuid): CourseDraft? = drafts[id]

    override suspend fun findPlan(id: Uuid): Course? = plans[id]

    override suspend fun planIds(): List<Uuid> = plans.keys.toList()

    override suspend fun saveDraft(draft: CourseDraft, expected: Revision?): Boolean {
        val stored = drafts[draft.id]
        val fits = if (expected == null) stored == null && draft.id !in plans && draft.id !in records
        else stored != null && stored.revision == expected
        if (!fits) return false
        drafts[draft.id] = draft
        changed()
        return true
    }

    override suspend fun discardDraft(id: Uuid): Boolean = (drafts.remove(id) != null).also { if (it) changed() }

    override fun observeRecords(): Flow<List<CourseRecordProjection>> =
        changes.map { records.values.map { it.projection() } }

    override fun observeRecord(id: Uuid): Flow<CourseRecordProjection?> = changes.map { records[id]?.projection() }

    override suspend fun findRecord(id: Uuid): CourseRecord? = records[id]

    override suspend fun rename(id: Uuid, title: String, note: String?): Boolean {
        // Названные колонки — и только они: у подделки записать их можно лишь целой записью,
        // остальное в ней остаётся прежним. Переход применяет сценарий (PLAN F5).
        val record = records[id] ?: return false
        records[id] = record.rename(title, note)
        changed()
        return true
    }

    override suspend fun courseHolding(packageId: Uuid): Uuid? =
        plans.values.firstOrNull { plan -> plan.sources.any { it.pkg.id == packageId } }?.id

    override suspend fun holdersOf(packageId: Uuid): List<Uuid> =
        plans.values.filter { plan -> plan.sources.any { it.pkg.id == packageId } }.map { it.id } +
            drafts.values.filter { draft -> draft.sources.any { it.pkg.id == packageId } }.map { it.id }

    override suspend fun planInProgress(id: Uuid): CourseInProgress? =
        plans[id]?.let { CourseInProgress(it, it.progress()) }

    override suspend fun recordReduction(reduction: CoverageReduction) {
        reductions += reduction
        changed()
    }

    override suspend fun amend(course: Course, expected: Revision): Boolean = replace(course, expected) {
        records[course.id]?.let { records[course.id] = it.withPrescription(course.prescription) }
    }

    override suspend fun reallocate(reallocation: CourseReallocation): Boolean =
        replace(reallocation.course, reallocation.expected)

    override suspend fun updateSources(course: Course, expected: Revision): Boolean = replace(course, expected)

    override suspend fun activate(activation: CourseDraft.Activation, planned: List<CourseIntake>) {
        drafts.remove(activation.course.id)
        plans[activation.course.id] = activation.course
        records[activation.record.id] = activation.record
        changed()
    }

    override suspend fun close(closing: CourseCompletion.Closing) {
        plans.remove(closing.record.id)
        records[closing.record.id] = closing.record
        changed()
    }

    private fun replace(course: Course, expected: Revision, also: () -> Unit = {}): Boolean {
        val stored = plans[course.id] ?: return false
        if (stored.revision != expected) return false
        plans[course.id] = course
        also()
        changed()
        return true
    }

    private fun changed() {
        changes.value++
    }
}

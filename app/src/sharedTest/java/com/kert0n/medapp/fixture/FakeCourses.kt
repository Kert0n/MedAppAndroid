package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.report.CourseInProgress
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.CoverageReduction
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.storage.course.CourseReallocation
import com.kert0n.medapp.storage.course.CourseStorageRepository
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Лечения в памяти — ровно столько, сколько спрашивают экраны локального учёта: карточке коробки
 * нужно **название** держащего её лечения, и больше ничего.
 *
 * Остальные двери порта падают, а не отвечают выдуманным: лечение — чужой агрегат со своими
 * правилами, и подделка, которая молча отвечает на вопрос о нём, превратила бы проверку экрана в
 * проверку выдумки. Что за этими дверями, проверяется на настоящем лечении (`feature/course`).
 */
class FakeCourses(vararg records: CourseRecordProjection) : CourseStorageRepository {

    private val stored = MutableStateFlow(records.associateBy { it.id })

    override fun observeRecord(id: Uuid): Flow<CourseRecordProjection?> = stored.map { it[id] }

    override fun observeRecords(): Flow<List<CourseRecordProjection>> = stored.map { it.values.toList() }

    override suspend fun courseHolding(packageId: Uuid): Uuid? = null

    override fun observeDrafts(): Flow<List<CourseDraftProjection>> = emptyFlow()

    override fun observePlan(id: Uuid): Flow<CourseProjection?> = emptyFlow()

    override fun observeCoverage(id: Uuid): Flow<CourseCoverage?> = emptyFlow()

    override fun observeCoverages(): Flow<Map<Uuid, CourseCoverage>> = emptyFlow()

    override fun observeReductions(courseId: Uuid): Flow<List<CoverageReduction>> = emptyFlow()

    override suspend fun reductionsSince(courseId: Uuid, since: Instant) = unasked()

    override suspend fun recentReductions(since: Instant) = unasked()

    override suspend fun findDraft(id: Uuid): CourseDraft? = unasked()

    override suspend fun findPlan(id: Uuid): Course? = unasked()

    override suspend fun planIds(): List<Uuid> = unasked()

    override suspend fun saveDraft(draft: CourseDraft, expected: Revision?): Boolean = unasked()

    override suspend fun discardDraft(id: Uuid): Boolean = unasked()

    override suspend fun findRecord(id: Uuid): CourseRecord? = unasked()

    override suspend fun rename(id: Uuid, title: String, note: String?): Boolean = unasked()

    override suspend fun holdersOf(packageId: Uuid): List<Uuid> = unasked()

    override suspend fun planInProgress(id: Uuid): CourseInProgress? = unasked()

    override suspend fun recordReduction(reduction: CoverageReduction) = unasked()

    override suspend fun amend(course: Course, expected: Revision): Boolean = unasked()

    override suspend fun reallocate(reallocation: CourseReallocation): Boolean = unasked()

    override suspend fun updateSources(course: Course, expected: Revision): Boolean = unasked()

    override suspend fun activate(activation: CourseDraft.Activation, planned: List<CourseIntake>) =
        unasked()

    override suspend fun close(closing: CourseCompletion.Closing) = unasked()

    private fun unasked(): Nothing = error("этой двери лечения экран локального учёта не открывает")
}

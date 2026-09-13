package com.kert0n.medapp.storage.course

import androidx.room.withTransaction
import com.kert0n.medapp.domain.course.Course
import com.kert0n.medapp.domain.course.CourseCompletion
import com.kert0n.medapp.domain.course.CourseDraft
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.IntakeAnswer
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.intake.toStorageEntity as toIntakeStorageEntity
import com.kert0n.medapp.storage.value.VocabularyDao
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CourseRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val courses: CourseDao,
    private val intakes: IntakeDao,
    private val vocabulary: VocabularyDao
) : CourseStorageRepository {

    override fun observeDrafts(): Flow<List<CourseDraftProjection>> =
        courses.observeDrafts().map { rows ->
            val words = vocabulary.snapshot()
            rows.map { it.toDraft(words).projection() }
        }

    override fun observePlan(id: Uuid): Flow<CourseProjection?> =
        courses.observePlan(id).map { row ->
            row?.takeUnless { it.isDraft }?.toPlan(vocabulary.snapshot())?.projection()
        }

    override suspend fun findDraft(id: Uuid): CourseDraft? =
        courses.findPlan(id)?.takeIf { it.isDraft }?.toDraft(vocabulary.snapshot())

    override suspend fun findPlan(id: Uuid): Course? =
        courses.findPlan(id)?.takeUnless { it.isDraft }?.toPlan(vocabulary.snapshot())

    override suspend fun saveDraft(draft: CourseDraft, expected: Revision?): Boolean = database.withTransaction {
        val existing = courses.findPlan(draft.id)
        if (existing != null && !existing.isDraft) return@withTransaction false
        // Запись эпизода живёт вечно, а план после конца лечения удаляется: «плана нет» само по
        // себе не значит «черновик ещё можно сохранить».
        if (existing == null && courses.findRecord(draft.id) != null) return@withTransaction false
        // Новый черновик не ложится поверх существующего, а правка — поверх чужой правки.
        when (expected) {
            null -> if (existing != null) return@withTransaction false
            else -> if (existing == null || existing.course.revision != expected.number) return@withTransaction false
        }
        courses.saveCourse(
            course = draft.toStorageEntity(),
            times = draft.schedule?.toTimeStorageEntities(draft.id).orEmpty(),
            sources = draft.medicine.toSourceStorageEntities(draft.id)
        )
        true
    }

    override suspend fun discardDraft(id: Uuid): Boolean = database.withTransaction {
        val existing = courses.findPlan(id) ?: return@withTransaction false
        if (!existing.isDraft) return@withTransaction false
        courses.deleteSourcesOf(id)
        courses.deleteTimesOf(id)
        courses.deletePlan(id)
        true
    }

    override fun observeRecords(): Flow<List<CourseRecordProjection>> =
        courses.observeRecords().map { rows ->
            val words = vocabulary.snapshot()
            rows.map { it.toDomain(words).projection() }
        }

    override fun observeRecord(id: Uuid): Flow<CourseRecordProjection?> =
        courses.observeRecord(id).map { it?.toDomain(vocabulary.snapshot())?.projection() }

    override suspend fun findRecord(id: Uuid): CourseRecord? =
        courses.findRecord(id)?.toDomain(vocabulary.snapshot())

    /**
     * Через переход записи, а не мимо него: что название не пустое и не длиннее предела, знает
     * `CourseRecord.rename`, и SQL это правило не повторяет. Записывается только то, что переход
     * и меняет, — законченное лечение обратно не открывается.
     */
    override suspend fun rename(id: Uuid, title: String, note: String?): Boolean = database.withTransaction {
        val record = courses.findRecord(id)?.toDomain(vocabulary.snapshot()) ?: return@withTransaction false
        val renamed = record.rename(title, note)
        courses.rename(renamed.id, renamed.title, renamed.note) > 0
    }

    override suspend fun courseHolding(packageId: Uuid): Uuid? = courses.courseHolding(packageId)

    override suspend fun reallocate(reallocation: CourseReallocation): Boolean = database.withTransaction {
        val course = reallocation.course
        if (courses.findPlan(course.id) == null) return@withTransaction false
        check(courses.sourcePackagesOf(course.id).toSet() == course.sources.map { it.pkg.id }.toSet()) {
            "пересчёт обеспечения не меняет состав пачек: смена состава — updateSources"
        }
        courses.updateAllocations(
            course.toStorageEntity(),
            course.medicine.toSourceStorageEntities(course.id),
            reallocation.expected
        )
    }

    override suspend fun updateSources(course: Course, expected: Revision): Boolean = database.withTransaction {
        // Состав правили из редакции, которой уже нет, — например, коробку из него выбросили, и
        // курс её уже потерял: писать некуда, и исключением это не является (PLAN F5).
        val stored = courses.findPlan(course.id) ?: return@withTransaction false
        if (stored.course.revision != expected.number) return@withTransaction false
        val revised = courses.updateAllocations(
            course.toStorageEntity(),
            course.medicine.toSourceStorageEntities(course.id),
            expected
        )
        if (!revised) return@withTransaction false
        courses.releaseAssignmentsOf(course.id)
        for (source in course.sources) {
            courses.assignPackage(ActivePackageAssignmentStorageEntity(source.pkg.id, course.id))
        }
        true
    }

    override suspend fun setTotalDoses(course: Course, expected: Revision): Boolean =
        courses.updateTotalDoses(
            id = course.id,
            totalDoses = course.totalDoses.count,
            expected = expected,
            revision = course.revision,
            updatedAt = course.updatedAt
        )

    override suspend fun activate(
        activation: CourseDraft.Activation,
        planned: List<CourseIntake>
    ) = database.withTransaction {
        val plan = activation.course
        courses.upsertRecord(activation.record.toStorageEntity())
        courses.saveCourse(
            course = plan.toStorageEntity(),
            times = plan.schedule.toTimeStorageEntities(plan.id),
            sources = plan.medicine.toSourceStorageEntities(plan.id)
        )
        for (source in plan.sources) {
            courses.assignPackage(ActivePackageAssignmentStorageEntity(source.pkg.id, plan.id))
        }
        intakes.insertPlannedIfMissing(planned.map { it.toIntakeStorageEntity() })
        Unit
    }

    override suspend fun close(closing: CourseCompletion.Closing) = database.withTransaction {
        val record = closing.record
        check(!record.isOpen) { "закрывается законченное лечение, а не идущее" }
        courses.upsertRecord(record.toStorageEntity())
        for (intake in closing.cancelled) {
            val cancellation = requireNotNull(intake.answer as? IntakeAnswer.Cancelled) {
                "конец лечения отменяет пункт, а не отвечает на него"
            }
            intakes.cancelIfPlanned(intake.id, cancellation.at)
        }
        courses.releaseAssignmentsOf(record.id)
        courses.deleteSourcesOf(record.id)
        courses.deletePlan(record.id)
    }
}

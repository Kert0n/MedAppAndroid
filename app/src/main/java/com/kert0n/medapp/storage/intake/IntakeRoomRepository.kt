package com.kert0n.medapp.storage.intake

import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.IntakeProjection
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.domain.intake.UnplannedIntake
import androidx.room.withTransaction
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.course.toSourceStorageEntities
import com.kert0n.medapp.storage.course.toStorageEntity as toCourseStorageEntity
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.chunkedForQuery
import com.kert0n.medapp.storage.database.observing
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.end
import com.kert0n.medapp.storage.pack.save
import com.kert0n.medapp.storage.value.VocabularyDao
import com.kert0n.medapp.storage.value.toStorageAmount
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class IntakeRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val intakes: IntakeDao,
    private val packages: PackageDao,
    private val courses: CourseDao,
    private val vocabulary: VocabularyDao
) : IntakeStorageRepository {

    override fun observeOfCourse(courseId: Uuid): Flow<List<IntakeProjection>> =
        database.observing("intakes", "package_records") { ofCourse(courseId).map { it.projection() } }

    override fun observeOfPackage(packageId: Uuid): Flow<List<IntakeProjection>> =
        database.observing("intakes", "package_records") {
            val words = vocabulary.snapshot()
            intakes.takenFrom(packageId).map { it.toDomain(words).projection() }
        }

    /** Строки и словарь — одной транзакцией: единица, записанная между ними, не потеряется. */
    override suspend fun ofCourse(courseId: Uuid): List<Intake> = database.withTransaction {
        val words = vocabulary.snapshot()
        intakes.ofCourse(courseId).map { it.toDomain(words) }
    }

    override suspend fun find(id: Uuid): Intake? = intakes.find(id)?.toDomain(vocabulary.snapshot())

    override suspend fun syncStateOf(id: Uuid): IntakeSyncState? = intakes.findEntity(id)?.syncState()

    override suspend fun save(recorded: RecordedIntake) =
        intakes.upsert(recorded.intake.toStorageEntity(recorded.sync))

    override suspend fun materialise(planned: List<CourseIntake>): Int =
        intakes.insertPlannedIfMissing(planned.map { it.toStorageEntity() })
            .count { it != -1L }

    override suspend fun plannedBefore(until: Instant): List<CourseIntake> =
        intakes.plannedBefore(until).let { rows ->
            val words = vocabulary.snapshot()
            rows.map { it.toDomain(words) as CourseIntake }
        }

    override suspend fun prunePlanned(courseId: Uuid, keep: Set<ScheduledOccurrence>): Int = database.withTransaction {
        // Тождество пункта — назначенные дата и время (PLAN F4), по ним и сверяется.
        val kept = keep.mapTo(HashSet()) { it.slot }
        val extra = intakes.plannedOf(courseId)
            .filter { (it.scheduledOn to it.scheduledTime) !in kept }
            .map { it.id }
        extra.chunkedForQuery().sumOf { intakes.deletePlanned(it) }
    }

    override suspend fun record(outcome: IntakeOutcome): Boolean = database.withTransaction {
        val intake = outcome.intake
        // Пачку читаем до ответа: списывать не из чего — значит и факта не записываем, иначе
        // приём разошёлся бы с остатком.
        val source = if (outcome.spendsLocally) {
            val taken = requireNotNull(outcome.taken) { "локальный расход есть только у принятого" }
            packages.find(taken.pkg.id) ?: return@withTransaction false
        } else {
            null
        }
        val applied = if (intake is UnplannedIntake) {
            intakes.insertIfMissing(intake.toStorageEntity(outcome.sync)) != -1L
        } else {
            val taken = intake.taken
            intakes.answerIfStatusIs(
                id = intake.id,
                from = outcome.expected.toList(),
                to = intake.status,
                at = outcome.answeredAt,
                packageId = taken?.pkg?.id,
                amount = taken?.amount?.quantity?.toStorageAmount(),
                unitId = intake.unit.id,
                accounting = outcome.sync.accounting,
                operationId = outcome.sync.operationId
            ) > 0
        }
        if (!applied) return@withTransaction false

        source?.let {
            val words = vocabulary.snapshot()
            when (val spent = it.toDomain(words).consume(requireNotNull(outcome.taken).amount)) {
                // Коробка кончилась. Приём и есть учётная запись о расходе: держится он за вечную
                // запись и конец переживает (PLAN D3, D6). Момент записи, а не ответа: редакцию
                // курса двигает то, когда мы узнали (PLAN D5).
                is PackageAfter.Ended -> packages.end(spent.ending, courses, words, outcome.recordedAt)
                // Расход не трогает обвязку доставки: версия и картина броней остаются прежними (E3).
                is PackageAfter.Left -> packages.save(spent.pkg, it.pack.syncState())
            }
        }
        outcome.reallocation?.let { (course, expected) ->
            courses.updateAllocations(
                course.toCourseStorageEntity(),
                course.medicine.toSourceStorageEntities(course.id),
                expected
            )
        }
        true
    }

}

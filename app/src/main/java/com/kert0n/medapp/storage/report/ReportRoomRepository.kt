package com.kert0n.medapp.storage.report

import androidx.room.withTransaction
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.chunkedForQuery
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.ZoneId
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReportRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val intakes: IntakeDao,
    private val courses: CourseDao,
    private val vocabulary: VocabularyDao
) : ReportStorageRepository {

    override fun observeSpending(period: SpendingPeriod, zone: ZoneId): Flow<Spending> =
        database.invalidationTracker.createFlow(*SPENDING_TABLES).map { spending(period, zone) }

    /** Приёмы и записи их эпизодов — одной транзакцией: отчёт о состоянии, которое было в базе. */
    private suspend fun spending(period: SpendingPeriod, zone: ZoneId): Spending = database.withTransaction {
        val words = vocabulary.snapshot()
        val taken = intakes.takenBetween(period.startsAt(zone), period.endsBefore(zone)).map { it.toDomain(words) }
        val episodeIds = taken.mapNotNull { it.courseIdOrNull() }.distinct()
        val records = episodeIds.chunkedForQuery()
            .flatMap { courses.recordsAmong(it) }
            .associate { it.record.id to it.toDomain(words).projection() }
        Spending.of(taken, records)
    }

    private fun Intake.courseIdOrNull(): Uuid? = (this as? CourseIntake)?.courseId

    private companion object {
        /** Из чего складывается истраченное: приёмы, записи эпизодов и записи о коробках. */
        val SPENDING_TABLES = arrayOf("intakes", "course_records", "course_times", "package_records")
    }
}

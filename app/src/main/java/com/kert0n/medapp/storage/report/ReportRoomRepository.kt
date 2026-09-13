package com.kert0n.medapp.storage.report

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.course.CourseProgress
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.report.CourseInProgress
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.domain.report.DayPlan
import com.kert0n.medapp.domain.report.FutureSpending
import com.kert0n.medapp.domain.report.Spending
import com.kert0n.medapp.domain.report.SpendingHorizon
import com.kert0n.medapp.domain.report.StockSummary
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.report.SpendingPeriod
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.chunkedForQuery
import com.kert0n.medapp.storage.database.observing
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReportRoomRepository @Inject constructor(
    private val database: MedAppDatabase,
    private val intakes: IntakeDao,
    private val packages: PackageDao,
    private val courses: CourseDao,
    private val vocabulary: VocabularyDao
) : ReportStorageRepository {

    override fun observeSpending(period: SpendingPeriod, zone: ZoneId): Flow<Spending> =
        database.observing(*SPENDING_TABLES) { spending(period, zone) }

    /** Приёмы и записи их эпизодов; транзакцию держит поток — отчёт о состоянии, которое было в базе. */
    private suspend fun spending(period: SpendingPeriod, zone: ZoneId): Spending {
        val words = vocabulary.snapshot()
        val taken = intakes.takenBetween(period.startsAt(zone), period.endsBefore(zone)).map { it.toDomain(words) }
        val episodeIds = taken.mapNotNull { it.courseIdOrNull() }.distinct()
        return Spending.of(taken, recordsOf(episodeIds, words))
    }

    override fun observeFutureSpending(horizon: SpendingHorizon): Flow<FutureSpending> =
        database.observing(*PLAN_TABLES) {
            val words = vocabulary.snapshot()
            val plans = plans(words)
            FutureSpending.of(plans, recordsOf(plans.map { it.course.id }, words), horizon)
        }

    override fun observeStockSummary(): Flow<StockSummary> =
        database.observing(*STOCK_TABLES) {
            val words = vocabulary.snapshot()
            StockSummary.of(packages.all().map { it.toDomain(words) })
        }

    override fun observeDayPlan(date: LocalDate, zone: ZoneId): Flow<DayPlan> =
        database.observing(*PLAN_TABLES) {
            val words = vocabulary.snapshot()
            val scheduled = intakes.scheduledOn(date).map { it.toDomain(words) as CourseIntake }
            val oneOffs = intakes.unplannedBetween(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
                .map { it.toDomain(words) as UnplannedIntake }
            val inProgress = plans(words)
            val records = recordsOf(scheduled.map { it.courseId } + inProgress.map { it.course.id }, words)
            DayPlan.of(date, scheduled, oneOffs, inProgress, records)
        }

    /** Идущие лечения с их прогрессом: пункты всех курсов — порциями, а не по курсу. */
    private suspend fun plans(words: Vocabulary): List<CourseInProgress> {
        val courses = this.courses.plans().map { it.toPlan(words) }
        val intakesByCourse = courses.map { it.id }.chunkedForQuery()
            .flatMap { intakes.ofCourses(it) }
            .map { it.toDomain(words) as CourseIntake }
            .groupBy { it.courseId }
        return courses.map { CourseInProgress(it, CourseProgress.of(intakesByCourse[it.id].orEmpty())) }
    }

    private suspend fun recordsOf(ids: List<Uuid>, words: Vocabulary): Map<Uuid, CourseRecordProjection> =
        ids.distinct().chunkedForQuery()
            .flatMap { courses.recordsAmong(it) }
            .associate { it.record.id to it.toDomain(words).projection() }

    private fun Intake.courseIdOrNull(): Uuid? = (this as? CourseIntake)?.courseId

    private companion object {
        /** Из чего складывается истраченное: приёмы, записи эпизодов и записи о коробках. */
        val SPENDING_TABLES = arrayOf("intakes", "course_records", "course_times", "package_records")

        /** Из чего складывается сводка: живые коробки, их сведения и записи. */
        val STOCK_TABLES = arrayOf("packages", "package_details", "package_records", "claims", "med_kits")

        /** Из чего складывается расход идущих лечений: планы, их времена и пункты, записи эпизодов. */
        val PLAN_TABLES = arrayOf("courses", "course_times", "course_sources", "intakes", "course_records", "package_records")
    }
}

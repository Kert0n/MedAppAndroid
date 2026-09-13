package com.kert0n.medapp.storage.intake

import androidx.room.Embedded
import androidx.room.Relation
import com.kert0n.medapp.domain.course.Revision
import com.kert0n.medapp.domain.course.ScheduledOccurrence
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.IntakeAnswer
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.intake.TakenDose
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.storage.pack.PackageRecordStorageEntity
import com.kert0n.medapp.storage.pack.PackageRefStorageRow
import com.kert0n.medapp.storage.value.storedDose
import com.kert0n.medapp.storage.value.storedUnit
import java.time.Instant

/**
 * Приём вместе со ссылками на пачки, которые он называет: плановую и фактическую — по записям о
 * коробках, поэтому история читается и после того, как коробки не стало (PLAN D6). Room читает
 * связи той же транзакцией — по запросу на связь на всю выборку, так что история из ста строк
 * не делает ста запросов.
 */
class IntakeStorageRow(
    @Embedded val intake: IntakeStorageEntity,
    @Relation(entity = PackageRecordStorageEntity::class, parentColumn = "planned_package_id", entityColumn = "id")
    val planned: PackageRefStorageRow? = null,
    @Relation(entity = PackageRecordStorageEntity::class, parentColumn = "taken_package_id", entityColumn = "id")
    val taken: PackageRefStorageRow? = null
) {
    fun toDomain(vocabulary: Vocabulary): Intake {
        val unit = vocabulary.storedUnit(intake.unitId)
        return if (intake.courseId == null) unplanned(unit, vocabulary) else scheduled(unit, vocabulary)
    }

    private fun scheduled(unit: QuantityUnit, vocabulary: Vocabulary): CourseIntake = CourseIntake(
        id = intake.id,
        courseId = requireNotNull(intake.courseId),
        courseRevision = Revision(requireNotNull(intake.courseRevision) {
            "пункт расписания порождён редакцией курса"
        }),
        slot = ScheduledOccurrence(
            localDate = requireNotNull(intake.scheduledOn) { "у пункта расписания есть исходная дата" },
            localTime = requireNotNull(intake.scheduledTime) { "у пункта расписания есть исходное время" },
            at = requireNotNull(intake.scheduledAt) { "у пункта расписания есть разрешённый момент" }
        ),
        plannedAmount = storedDose(
            requireNotNull(intake.plannedAmount) { "у пункта расписания есть плановая доза" },
            unit
        ),
        plannedPackage = intake.plannedPackageId?.let {
            requireNotNull(planned) { "плановая пачка $it приёма не найдена" }.toRef(vocabulary)
        },
        answer = answer(unit, vocabulary)
    )

    private fun unplanned(unit: QuantityUnit, vocabulary: Vocabulary): UnplannedIntake =
        UnplannedIntake(id = intake.id, dose = requireNotNull(takenDose(unit, vocabulary)) {
            "внеплановый приём состоялся по определению: другого статуса у него не бывает"
        })

    private fun answer(unit: QuantityUnit, vocabulary: Vocabulary): IntakeAnswer? = when (intake.status) {
        IntakeStatus.PLANNED -> null
        IntakeStatus.TAKEN -> IntakeAnswer.Taken(requireNotNull(takenDose(unit, vocabulary)))
        IntakeStatus.MISSED -> IntakeAnswer.Missed(answeredMoment())
        IntakeStatus.CANCELLED -> IntakeAnswer.Cancelled(answeredMoment())
    }

    private fun answeredMoment(): Instant =
        requireNotNull(intake.answeredAt) { "у отвеченного приёма есть момент ответа" }

    /**
     * Коробки у состоявшегося приёма может уже не быть: ссылка держится за запись о ней, и та
     * никуда не девается (`RESTRICT`, PLAN D6, F1). Количество и момент записаны в строке приёма.
     */
    private fun takenDose(unit: QuantityUnit, vocabulary: Vocabulary): TakenDose? {
        val amount = intake.takenAmount ?: return null
        return TakenDose(
            pkg = requireNotNull(taken) { "принятый приём называет запись о пачке: ${intake.takenPackageId}" }
                .toRef(vocabulary),
            amount = storedDose(amount, unit),
            at = answeredMoment()
        )
    }
}

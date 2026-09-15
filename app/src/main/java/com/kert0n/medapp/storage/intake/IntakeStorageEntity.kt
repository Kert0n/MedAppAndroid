package com.kert0n.medapp.storage.intake

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.IntakeStatus
import com.kert0n.medapp.domain.intake.UnplannedIntake
import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.storage.course.CourseRecordStorageEntity
import com.kert0n.medapp.storage.pack.PackageRecordStorageEntity
import com.kert0n.medapp.storage.value.toStorageAmount
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * План и факт — одна запись: запланированный приём это приём, который ещё не состоялся. Вид
 * различается наличием курса, а не колонкой-признаком (PLAN D6, F1).
 *
 * `status` хранится, хотя домен выводит его из ответа: подтверждение идёт условным `UPDATE` по
 * ожидаемому статусу, и без колонки условие писать не по чему (PLAN F2). Пачки — плановая и
 * фактическая — колонками; в домен их собирает `IntakeStorageRow` связями. Аптечка на момент
 * события отдельно не записывается: она у пачки.
 *
 * `accounting` и `operation_id` живут в той же строке, но доменная модель их не носит: это
 * `IntakeSyncState` сетевого слоя, и правила о приёме его не читают (PLAN D6).
 *
 * Ключи на записи — о коробке и об эпизоде — `RESTRICT`: приём держится за то, что остаётся
 * навсегда, и история не удаляется ни каскадом, ни вслед за коробкой (PLAN D3, F2).
 */
@Entity(
    tableName = "intakes",
    foreignKeys = [
        ForeignKey(
            entity = CourseRecordStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PackageRecordStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["planned_package_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PackageRecordStorageEntity::class,
            parentColumns = ["id"],
            childColumns = ["taken_package_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["course_id", "scheduled_on", "scheduled_time"], unique = true),
        Index("planned_package_id"),
        Index("taken_package_id"),
        Index("operation_id"),
        Index("answered_at"),
        // Просроченные пункты сверка находит по состоянию и сроку, а не перебором года приёмов (PLAN D8).
        Index(value = ["status", "scheduled_at"])
    ]
)
class IntakeStorageEntity(
    @PrimaryKey val id: Uuid,
    @ColumnInfo(name = "unit_id") val unitId: Uuid,
    val status: IntakeStatus,
    @ColumnInfo(name = "course_id") val courseId: Uuid? = null,
    @ColumnInfo(name = "course_revision") val courseRevision: Long? = null,
    @ColumnInfo(name = "scheduled_on") val scheduledOn: LocalDate? = null,
    @ColumnInfo(name = "scheduled_time") val scheduledTime: LocalTime? = null,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Instant? = null,
    @ColumnInfo(name = "planned_amount") val plannedAmount: String? = null,
    @ColumnInfo(name = "planned_package_id") val plannedPackageId: Uuid? = null,
    @ColumnInfo(name = "answered_at") val answeredAt: Instant? = null,
    @ColumnInfo(name = "taken_package_id") val takenPackageId: Uuid? = null,
    @ColumnInfo(name = "taken_amount") val takenAmount: String? = null,
    val accounting: IntakeAccounting = IntakeAccounting.NOT_APPLICABLE,
    @ColumnInfo(name = "operation_id") val operationId: Uuid? = null
) {
    fun syncState(): IntakeSyncState = IntakeSyncState(
        intakeId = id,
        accounting = accounting,
        operationId = operationId
    )
}

fun Intake.toStorageEntity(sync: IntakeSyncState = IntakeSyncState(id)): IntakeStorageEntity {
    require(sync.intakeId == id) { "обвязка синхронизации принадлежит своему приёму" }
    val takenDose = taken
    val common = IntakeStorageEntity(
        id = id,
        unitId = unit.id,
        status = status,
        answeredAt = answerMoment(),
        takenPackageId = takenDose?.pkg?.id,
        takenAmount = takenDose?.amount?.quantity?.toStorageAmount(),
        accounting = sync.accounting,
        operationId = sync.operationId
    )
    return when (this) {
        is UnplannedIntake -> common
        is CourseIntake -> IntakeStorageEntity(
            id = common.id,
            unitId = common.unitId,
            status = common.status,
            courseId = courseId,
            courseRevision = courseRevision.number,
            scheduledOn = slot.localDate,
            scheduledTime = slot.localTime,
            scheduledAt = slot.at,
            plannedAmount = plannedAmount.quantity.toStorageAmount(),
            plannedPackageId = plannedPackage?.id,
            answeredAt = common.answeredAt,
            takenPackageId = common.takenPackageId,
            takenAmount = common.takenAmount,
            accounting = common.accounting,
            operationId = common.operationId
        )
    }
}

private fun Intake.answerMoment(): Instant? = when (this) {
    is UnplannedIntake -> dose.at
    is CourseIntake -> answer?.at
}

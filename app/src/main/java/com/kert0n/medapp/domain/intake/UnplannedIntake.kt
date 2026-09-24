package com.kert0n.medapp.domain.intake

import com.kert0n.medapp.domain.value.QuantityUnit
import kotlin.uuid.Uuid

/**
 * Внеплановый приём — разовый факт вне курса. Он бывает только состоявшимся: плана у него нет,
 * поэтому нет и полей плана, а [status] всегда [IntakeStatus.TAKEN].
 */
class UnplannedIntake(
    override val id: Uuid,
    val dose: TakenDose
) : Intake {

    /** Единица НА МОМЕНТ СОБЫТИЯ: берётся у самого факта, второго поля для неё не нужно. */
    override val unit: QuantityUnit get() = dose.amount.unit

    override val status: IntakeStatus get() = IntakeStatus.TAKEN

    override val taken: TakenDose get() = dose

    override fun projection(): IntakeProjection.Unplanned = IntakeProjection.Unplanned(id, dose)

    /** Тождество — [id]: запись остаётся той же записью. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is UnplannedIntake && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "UnplannedIntake(id=$id, at=${dose.at})"

}

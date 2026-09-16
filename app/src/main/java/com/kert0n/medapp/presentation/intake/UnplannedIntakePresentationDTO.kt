package com.kert0n.medapp.presentation.intake

import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import com.kert0n.medapp.presentation.value.toDomain

/**
 * Что человек набрал в листе разового приёма (PLAN H3 №10): сколько он принял. Единица не
 * набирается — её знает коробка, и другой у этого приёма быть не может.
 */
data class UnplannedIntakePresentationDTO(val amount: String = "")

/** Чем кончился разбор набранного или сама запись: у каждой беды своё поле и свои слова. */
sealed interface UnplannedIntakeError {

    /** Число не разобралось; чем именно — говорит разбор величины. */
    data class Amount(val error: QuantityPresentationError) : UnplannedIntakeError

    /** Сценарий отверг приём: в коробке столько не наберётся, её нет, единица не та (PLAN D6). */
    data class Rejected(val reason: IntakeRejected.Reason) : UnplannedIntakeError
}

/**
 * Разбор набранного: строка экрана — в дозу, которую примет сценарий. Единицу приносит коробка, а
 * не поле: домен требует готовую величину, и собирать её из чужих частей экран не вправе (PLAN H1).
 *
 * Пустое поле — это `EMPTY` от разбора величины, а не «принял ноль»: ноля приёма не бывает.
 */
fun UnplannedIntakePresentationDTO.parsed(
    unit: UnitPresentationDTO,
    vocabulary: Vocabulary
): ParsedInput<Dose, UnplannedIntakeError> =
    when (val parsed = QuantityPresentationDTO(amount, unit).toDomain(vocabulary)) {
        is ParsedInput.Rejected -> ParsedInput.Rejected(UnplannedIntakeError.Amount(parsed.error))
        is ParsedInput.Parsed -> ParsedInput.Parsed(Dose(parsed.value))
    }

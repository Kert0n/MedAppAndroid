package com.kert0n.medapp.feature.scan

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.scan.CodeFormat
import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.scan.PackageCodes
import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.domain.scan.ScannedCode
import javax.inject.Inject

/**
 * Человек навёл камеру на коробку (ТЗ 4.1.1.3.2; PLAN H5). Код даёт **предложение, а не факт**:
 * что подставить в форму новой пачки, решает человек на экране 25. Спрашивают только о DataMatrix
 * «Честного знака»: EAN-13 описывает товар, а не эту коробку, QR — приглашение в полку и идёт
 * своим путём, остальное — «код не поддерживается», и запроса нет (C1). Один код в потоке камеры —
 * один запрос: это держит экран, сценарий отвечает на тот код, с которым его позвали.
 */
class PackageScanning @Inject constructor(
    private val codes: PackageCodes
) {

    suspend fun lookup(code: ScannedCode): Outcome {
        if (code.format != CodeFormat.DATA_MATRIX || code.text.isEmpty()) return Outcome.Unsupported
        return when (val lookup = codes.lookup(DataMatrixCode(code.text))) {
            is PackageCodes.Lookup.Found -> Outcome.Suggested(lookup.suggestion)
            PackageCodes.Lookup.NotFound -> Outcome.NotFound
            is PackageCodes.Lookup.Unavailable -> Outcome.Unavailable(lookup.reason)
        }
    }

    /** Чем кончилось — те случаи, которые экран показывает по-разному. */
    sealed interface Outcome {

        data class Suggested(val suggestion: PackageSuggestion) : Outcome

        /** Реестр код не знает: форма открывается пустой, пачку заводят руками. */
        data object NotFound : Outcome

        /** Не DataMatrix «Честного знака»: спрашивать не о чем, и запроса не было. */
        data object Unsupported : Outcome

        data class Unavailable(val reason: Unavailability) : Outcome
    }
}

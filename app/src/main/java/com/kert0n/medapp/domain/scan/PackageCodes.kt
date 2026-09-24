package com.kert0n.medapp.domain.scan

import com.kert0n.medapp.domain.Unavailability

/**
 * Что говорит код с коробки. Спросить можно только реестр маркировки, поэтому действие выполняет
 * сеть, а домен называет его и исходы (PLAN H1, H5) — как справочник у `PackageTemplates`.
 */
interface PackageCodes {

    suspend fun lookup(code: DataMatrixCode): Lookup

    /** Ровно те случаи, которые сценарий и экран разбирают по-разному. */
    sealed interface Lookup {

        data class Found(val suggestion: PackageSuggestion) : Lookup

        /** Реестр код не знает — обычный ответ, а не ошибка: пачку заводят руками. */
        data object NotFound : Lookup

        data class Unavailable(val reason: Unavailability) : Lookup
    }
}

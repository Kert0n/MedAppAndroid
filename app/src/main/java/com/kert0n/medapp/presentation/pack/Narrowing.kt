package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.value.FormPresentationDTO

/**
 * Чем человек сузил список (PLAN H4). Сужение **ровно одно**: два одновременных сузили бы
 * список до пустого чаще, чем помогли.
 *
 * Своё перечисление, а не `PackageQuery.Filter` хранилища: экран не видит хранилище (границы
 * H1), и «скоро истекает» у него — вид сужения, а не число дней, которое знает домен.
 */
sealed interface Narrowing {

    data object Expired : Narrowing

    data object ExpiringSoon : Narrowing

    data object OnCourse : Narrowing

    data object HasFree : Narrowing

    data class OfCategory(val category: String) : Narrowing

    data class OfForm(val form: FormPresentationDTO) : Narrowing
}

/**
 * Каким порядком показывать. Порядок **есть всегда**, и снимать его не во что — этим он и
 * отличается от сужения (PLAN H3).
 */
enum class Ordering { NAME, EXPIRY, ADDED_AT, QUANTITY }

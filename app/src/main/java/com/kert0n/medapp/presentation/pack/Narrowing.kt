package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.presentation.value.FormPresentationDTO

/**
 * Чем человек сузил список. Своё перечисление, а не `PackageQuery.Filter` из хранения: экран о
 * хранении не знает (граница H1), и знать ему нужно не «какой запрос уйдёт в базу», а «какая
 * кнопка нажата». Форма едет величиной с именем — её же и рисует надпись на кнопке.
 *
 * Сужение ровно одно: два одновременных сузили бы список до пустого чаще, чем помогли (PLAN H4).
 */
sealed interface Narrowing {

    data object Expired : Narrowing

    data object ExpiringSoon : Narrowing

    data object OnCourse : Narrowing

    data object HasFree : Narrowing

    data class OfCategory(val category: String) : Narrowing

    data class OfForm(val form: FormPresentationDTO) : Narrowing
}

/** Чем человек упорядочил список. По количеству — от меньшего: список ведёт к тому, что кончается. */
enum class Ordering { NAME, EXPIRY, ADDED_AT, QUANTITY }

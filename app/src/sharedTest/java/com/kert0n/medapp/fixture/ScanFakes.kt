package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.scan.PackageCodes
import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.domain.scan.ScannedCategory
import kotlinx.coroutines.CompletableDeferred

/**
 * Реестр «Честного знака» для проверок: живой спрашивают одна проба и только по явной просьбе
 * (AGENTS «Бережно к чужому API»).
 *
 * Отвечает тем, что положили, и **падает, когда не положили ничего**: проверка, не ждавшая запроса
 * в реестр, должна о нём узнать, а не получить тихое «не найдено» (разбор #55). Спрошенное
 * запоминается — «один код — один запрос» иначе нечем проверить.
 */
class FakePackageCodes(
    var answer: PackageCodes.Lookup? = null
) : PackageCodes {

    val asked = mutableListOf<DataMatrixCode>()

    private var held: CompletableDeferred<Unit>? = null

    override suspend fun lookup(code: DataMatrixCode): PackageCodes.Lookup {
        asked += code
        held?.await()
        return requireNotNull(answer) { "реестр спрошен о $code, а проверка ответа не задавала" }
    }

    /** Ответ не приходит, пока проверка его не отпустит: так видно форму до ответа реестра. */
    fun hold() {
        held = CompletableDeferred()
    }

    fun release() {
        requireNotNull(held) { "ответ реестра не задерживали" }.complete(Unit)
    }

    companion object {

        /** Что реестр рассказал о настоящей коробке: снято с ответа пробы 2026-09-14. */
        fun found(
            name: String = "Цетрин",
            formText: String? = "таблетки, покрытые плёночной оболочкой",
            quantityText: String? = "20 таблеток в 2 блистерах",
            category: ScannedCategory = ScannedCategory.MEDICINE
        ) = PackageCodes.Lookup.Found(
            PackageSuggestion(
                name = name,
                formText = formText,
                manufacturer = "Dr. Reddy’s",
                country = "Индия",
                activeSubstance = "цетиризин",
                dosageText = "10 мг",
                quantityText = quantityText,
                category = category
            )
        )
    }
}

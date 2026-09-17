package com.kert0n.medapp.presentation.pack

import com.kert0n.medapp.domain.scan.PackageSuggestion
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Предложение кода — в поля формы новой коробки (PLAN H3 «Набор сканера»). Сканер ничего не
 * показывает от себя: он **предзаполняет обычный экран**, и дальше человек идёт привычным путём
 * (решение владельца 2026-09-17).
 *
 * **Берётся всё, что реестр сказал.** Название, форма, производитель, страна, срок годности и день
 * покупки ложатся в свои поля; количество — в своё, когда его можно прочитать числом; остальное —
 * в описание, теми же словами, какими его назвал реестр. Оставить сказанное за бортом значит
 * заставить человека переписывать с коробки то, что приложение уже прочитало.
 *
 * Категория — единственное, что заполняется не здесь: реестр называет её по-своему («drugs»), а
 * человеку она нужна его словами, и слова эти живут в `R.string` у экрана (PLAN H1).
 *
 * **Заполняется только пустое**: ответ приходит из сети, и к этому времени человек уже мог начать
 * печатать — затереть набранное им значило бы спорить с тем, кто держит коробку в руках
 * (U1 «ввод не затирается значением, дочитанным из базы»).
 *
 * **Выдумывать нечем**: форма подставляется, только когда её узнал словарь (наугад она не дала бы
 * подключить коробку к лечению), количество — только когда это одно число, а дозировка вещества
 * разовой дозой не становится никогда: «10 мг» — состав таблетки, а не то, сколько их пить, и
 * меряются они разными единицами (PLAN H5).
 */
fun PackageSuggestion.filling(form: PackageFormPresentationDTO, words: Vocabulary): PackageFormPresentationDTO {
    val counted = quantityText?.let { counted(it, words) }
    return form.copy(
        name = form.name.ifBlank { name.orEmpty() },
        form = form.form ?: this.form?.toPresentationDTO(),
        manufacturer = form.manufacturer.ifBlank { manufacturer.orEmpty() },
        country = form.country.ifBlank { country.orEmpty() },
        expiresOn = form.expiresOn.ifBlank { expiresOn?.toPresentationDTO()?.text.orEmpty() },
        amount = form.amount.ifBlank { counted?.amount.orEmpty() },
        unit = form.unit ?: counted?.unit?.toPresentationDTO(),
        description = form.description.ifBlank { told(counted) },
        // День продажи и есть день покупки: через реестр прошёл кассовый чек.
        purchasedOn = form.purchasedOn ?: boughtOn
    )
}

/**
 * Что реестр сказал словами, а поля для этого нет: текст формы, которую не узнал словарь,
 * действующее вещество, дозировка и количество, не ставшее числом. Слова — реестра, приложение
 * только складывает их через запятую: сочинять за реестр оно не станет.
 */
private fun PackageSuggestion.told(counted: Counted?): String = listOfNotNull(
    formText.takeIf { form == null },
    activeSubstance,
    dosageText,
    quantityText.takeIf { counted == null }
).joinToString(", ")

/** Количество, прочитанное из слов реестра. */
private class Counted(val amount: String, val unit: QuantityUnit?)

/**
 * «30 шт» — это тридцать штук, и переписывать их человеку незачем. А «20 таблеток в 2 блистерах»
 * числом не становится: чисел там два, и ни одно из них не остаток коробки — подставь любое, и
 * коробка заведётся с тем количеством, которого в ней нет (PLAN H5). Поэтому читается **одно**
 * число со словом единицы и больше ничего.
 *
 * Единица берётся у словаря по её точному имени: своей клиент не заводит. Не узнал — остаётся
 * число, а единицу называет человек: число от этого не портится, оно у коробки одно.
 */
private fun counted(text: String, words: Vocabulary): Counted? {
    val read = COUNT.matchEntire(text.trim()) ?: return null
    val (amount, named) = read.destructured
    return Counted(amount.replace(',', '.'), words.unitWithName(named.trim().trimEnd('.')))
}

/** Число, за которым стоит не больше одного слова: «30 шт», «100 мл», «20». */
private val COUNT = Regex("""(\d+(?:[.,]\d+)?)\s*(\p{L}*\.?)""")

package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid

/**
 * Снимок общего словаря единиц и форм: по идентификатору — объект. Словарь принадлежит серверу и
 * только растёт, поэтому промах значит «снимок устарел», а не «такого нет»: кто держит снимок,
 * тот его и дочитывает, а домен получает уже объекты. Величина: два снимка с одним составом —
 * один и тот же словарь, а состав — множество записей по их тождеству, то есть по `id`:
 * переименование на сервере словарь не меняет, оно меняет сведения о той же записи.
 */
class Vocabulary(units: Collection<QuantityUnit>, forms: Collection<DosageForm>) {

    private val units: Map<Uuid, QuantityUnit> = units.associateBy { it.id }

    private val forms: Map<Uuid, DosageForm> = forms.associateBy { it.id }

    init {
        require(this.units.size == units.size) { "единица в снимке словаря заведена дважды" }
        require(this.forms.size == forms.size) { "форма в снимке словаря заведена дважды" }
    }

    /**
     * Известна ли хоть одна единица. Без неё не показать ни одного количества, а форма без
     * единицы количества не измеряет: «словарь не пуст» — это не то же самое (PLAN D1).
     */
    val knowsUnits: Boolean get() = units.isNotEmpty()

    /** `null` — единицы в снимке нет: он старее, чем тот, кто её назвал. */
    fun unit(id: Uuid): QuantityUnit? = units[id]

    fun form(id: Uuid): DosageForm? = forms[id]

    /**
     * Какую форму словаря называет текст [text] (PLAN H5). Словарь и чужой текст получены разными
     * путями, и дословное совпадение — удача; сравниваются основы слов ([DosageForm.stems]):
     * совпали целиком — форма; нет — `null`: либо даём то, что узнали, либо не даём ничего,
     * догадок и выбора из похожих нет (решение владельца 2026-09-14). Косая черта между словами —
     * альтернативы: узнана ровно одна — она; обе — это выбор, а не ответ, `null`.
     */
    fun formNamed(text: String): DosageForm? =
        DosageForm.alternatives(text).mapNotNull { wanted -> forms.values.firstOrNull { it.stems == wanted } }.distinct().singleOrNull()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Vocabulary && units == other.units && forms == other.forms)

    override fun hashCode(): Int = 31 * units.hashCode() + forms.hashCode()

    override fun toString(): String = "Vocabulary(${units.size} единиц, ${forms.size} форм)"

    companion object {
        val empty: Vocabulary = Vocabulary(emptyList(), emptyList())
    }
}

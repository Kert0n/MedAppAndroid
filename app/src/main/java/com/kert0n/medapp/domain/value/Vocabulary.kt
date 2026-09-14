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
     * Какую форму словаря называет текст [text] (PLAN H5) — **лучшая догадка**, одна: она сразу
     * подставляется, а ошибётся — человек поправит сам (решение владельца 2026-09-14). Словарь и
     * чужой текст получены разными путями, поэтому сравниваются основы слов ([DosageForm.stems]):
     * совпали целиком — она; иначе самая длинная форма, с которой текст начинается («таблетки
     * шипучие» → «таблетки»); иначе самая короткая форма, которая начинается с текста («капли» →
     * «капли глазные»); иначе самая короткая с той же основой первого слова; ничего похожего —
     * `null`. Косая черта между словами — альтернативы: догадка по первой, которая что-то дала.
     */
    fun formNamed(text: String): DosageForm? =
        DosageForm.alternatives(text).firstNotNullOfOrNull { wanted -> guess(wanted) }

    private fun guess(wanted: List<String>): DosageForm? {
        val known = forms.values
        known.firstOrNull { it.stems == wanted }?.let { return it }
        known.filter { wanted.startsWith(it.stems) }.maxByOrNull { it.stems.size }?.let { return it }
        known.filter { it.stems.startsWith(wanted) }.minByOrNull { it.stems.size }?.let { return it }
        return known.filter { it.stems.first() == wanted.first() }.minByOrNull { it.stems.size }
    }

    private fun List<String>.startsWith(prefix: List<String>): Boolean =
        prefix.size <= size && subList(0, prefix.size) == prefix

    override fun equals(other: Any?): Boolean =
        this === other || (other is Vocabulary && units == other.units && forms == other.forms)

    override fun hashCode(): Int = 31 * units.hashCode() + forms.hashCode()

    override fun toString(): String = "Vocabulary(${units.size} единиц, ${forms.size} форм)"

    companion object {
        val empty: Vocabulary = Vocabulary(emptyList(), emptyList())
    }
}

package com.kert0n.medapp.domain.value

import kotlin.uuid.Uuid

/**
 * Снимок общего словаря единиц и форм: по идентификатору — объект. Словарь принадлежит серверу,
 * и промах значит либо «снимок устарел», либо «такой записи нет вовсе»: состав словаря сервер
 * меняет сам (C1 «Формы — базовые виды»). Кто держит снимок, тот его и дочитывает, а домен
 * получает уже объекты. Величина: два снимка с одним составом —
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

    /** `null` — единицы в снимке нет. */
    fun unit(id: Uuid): QuantityUnit? = units[id]

    fun form(id: Uuid): DosageForm? = forms[id]

    /** Единица из снимка — или промах, который разбор наверх не глотает. */
    fun unitOrMiss(id: Uuid): QuantityUnit = unit(id) ?: throw VocabularyMiss(VocabularyMiss.Kind.UNIT, id)

    fun formOrMiss(id: Uuid): DosageForm = form(id) ?: throw VocabularyMiss(VocabularyMiss.Kind.FORM, id)

    /** Все единицы снимка — тому, кто кладёт словарь в базу целиком. */
    val allUnits: Collection<QuantityUnit> get() = units.values

    val allForms: Collection<DosageForm> get() = forms.values

    /**
     * Форма по её точному имени. Нужна тем, кто узнал **название** вида из чужого текста и
     * должен взять объект у сервера, а не завести свой: тождество формы — серверный `id`, и
     * придумать его клиенту нечем (PLAN D1, H5).
     */
    fun formWithName(name: String): DosageForm? = forms.values.firstOrNull { it.name == name }

    /**
     * Единица по её точному имени — тем же доводом, что и [formWithName]: «Честный знак» называет
     * количество словами («30 шт»), и единицу из этих слов надо взять у сервера, а не завести свою
     * (PLAN H5). Промах значит «такой единицы словарь не знает», и тогда её называет человек.
     */
    fun unitWithName(name: String): QuantityUnit? = units.values.firstOrNull { it.name == name }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Vocabulary && units == other.units && forms == other.forms)

    override fun hashCode(): Int = 31 * units.hashCode() + forms.hashCode()

    override fun toString(): String = "Vocabulary(${units.size} единиц, ${forms.size} форм)"

    companion object {
        val empty: Vocabulary = Vocabulary(emptyList(), emptyList())
    }
}

package com.kert0n.medapp.network.crpt

import com.kert0n.medapp.domain.scan.FormSuggestion
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Vocabulary
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ответ становится предложением, а не фактом (PLAN H5): непонятное остаётся незаполненным, форма —
 * из словаря или выбор, категория вне лекарств — предупреждение, количество и дозировка — строки.
 */
class CrptSuggestionTest {

    private val tablets = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000101"), "таблетки")
    private val coated = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000102"), "таблетки покрытые пленочной оболочкой")
    private val cream = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000103"), "крем")
    private val words = Vocabulary(emptyList(), listOf(tablets, coated, cream))

    /** Тот же нестрогий разбор, что у клиента (G3). */
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun dto(text: String): CrptCheckNetworkDTO = json.decodeFromString(CrptCheckNetworkDTO.serializer(), text)

    /** Живой ответ 2026-09-14: полночь 31 марта по Москве — в UTC ещё 30-е, страна — фишкой карточки. */
    @Test
    fun aFoundMedicineFillsWhatTheAnswerNamesAndNothingElse() {
        val suggestion = dto(CrptFixtures.found).toSuggestion(words)

        assertEquals("Цетрин", suggestion.name)
        assertEquals("таблетки покрытые пленочной оболочкой", suggestion.formText)
        assertEquals(FormSuggestion.One(coated), suggestion.form)
        assertEquals("Д-Р РЕДДИ`С ЛАБОРАТОРИС ЛТД.", suggestion.manufacturer)
        assertEquals("ИНДИЯ", suggestion.country)
        assertEquals(LocalDate.of(2028, 3, 31), suggestion.expiresOn?.lastDay)
        assertEquals("цетиризин", suggestion.activeSubstance)
        // Строки для глаз: числом они не становятся — у предложения нет полей для этого.
        assertEquals("10 мг", suggestion.dosageText)
        assertEquals("30 шт", suggestion.quantityText)
        assertTrue(suggestion.isMedicine)
    }

    /**
     * Составная дозировка «1.5 мг+1 мг+0.5 мг» остаётся строкой — у предложения нет поля, куда её
     * превратить; блоков в ответе меньше — предложение не беднее, чем ответ.
     */
    @Test
    fun aCompoundDosageStaysTextAndFewerBlocksAreNotAnError() {
        val lozenges = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000105"), "таблетки для рассасывания")
        val suggestion = dto(CrptFixtures.lozenge).toSuggestion(Vocabulary(emptyList(), listOf(tablets, coated, lozenges)))

        assertEquals("Доритрицин", suggestion.name)
        assertEquals(FormSuggestion.One(lozenges), suggestion.form)
        assertEquals("1.5 мг+1 мг+0.5 мг", suggestion.dosageText)
        assertEquals("ГЕРМАНИЯ", suggestion.country)
        assertEquals("МЕДИЦЕ ФАРМА ГМБХ & КО. КГ", suggestion.manufacturer)
        assertEquals(LocalDate.of(2028, 9, 30), suggestion.expiresOn?.lastDay)
    }

    /** Категория вне лекарств — предупреждение; форма при этом подставляется, если словарь её знает. */
    @Test
    fun aCosmeticIsNotAMedicine() {
        val suggestion = dto(CrptFixtures.cosmetics).toSuggestion(words)

        assertFalse(suggestion.isMedicine)
        assertEquals("Крем для рук", suggestion.name)
        assertEquals(FormSuggestion.One(cream), suggestion.form)
        assertNull(suggestion.expiresOn)
        assertNull(suggestion.manufacturer)
    }

    /**
     * Форма, которой словарь не знает, — пусто, но текст реестра остаётся: человек его видит.
     * «Таблетки» без точного имени в словаре — выбор из всех таблеток.
     */
    @Test
    fun anUnknownFormStaysEmptyAndAnAmbiguousOneIsAChoice() {
        val unknown = dto("""{"codeFounded": true, "category": "drugs", "screen": {"items": [{"pharmacyData": {"form": "пластырь"}}]}}""").toSuggestion(words)
        assertEquals(FormSuggestion.None, unknown.form)
        assertEquals("пластырь", unknown.formText)

        val sublingual = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000104"), "таблетки подъязычные")
        val withoutPlainTablets = Vocabulary(emptyList(), listOf(coated, sublingual, cream))
        val ambiguous = dto("""{"codeFounded": true, "category": "drugs", "screen": {"items": [{"attrList": [{"label": "Форма выпуска", "value": "Таблетки"}]}]}}""")
        assertEquals(FormSuggestion.Several(listOf(sublingual, coated)), ambiguous.toSuggestion(withoutPlainTablets).form)
    }

    /** Без аптечного блока и атрибутов — только имя: остальное не придумывается. */
    @Test
    fun aBareAnswerSuggestsOnlyTheName() {
        val suggestion = dto("""{"codeFounded": true, "category": "bio", "productName": "Омега-3"}""").toSuggestion(words)

        assertEquals("Омега-3", suggestion.name)
        assertEquals(FormSuggestion.None, suggestion.form)
        assertNull(suggestion.manufacturer)
        assertNull(suggestion.country)
        assertNull(suggestion.dosageText)
        assertNull(suggestion.quantityText)
        assertTrue(suggestion.isMedicine)
    }
}

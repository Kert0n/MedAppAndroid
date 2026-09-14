package com.kert0n.medapp.domain.value

import java.io.File
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сравнение по основам слов не должно склеивать разные формы словаря: «раствор для
 * внутривенного введения» и «для внутримышечного» — разные вещи. На встроенном словаре каждую
 * форму её собственное имя называет однозначно (PLAN H5). Красная проверка: укоротить основу до
 * пяти букв — «внутривенного» и «внутримышечного» стали бы одной основой «внутр».
 */
class BundledVocabularyFormsTest {

    private val asset: File = listOf(
        File("src/main/assets/vocabulary.json"),
        File("app/src/main/assets/vocabulary.json")
    ).firstOrNull { it.isFile } ?: error("встроенного словаря нет — проверка прошла бы впустую")

    private val forms: List<DosageForm> = Json.parseToJsonElement(asset.readText()).jsonObject
        .getValue("formTypes").jsonArray
        .map { DosageForm(Uuid.parse(it.jsonObject.getValue("id").jsonPrimitive.content), it.jsonObject.getValue("name").jsonPrimitive.content) }

    @Test
    fun everyBundledFormNamesItselfExactly() {
        val words = Vocabulary(emptyList(), forms)
        for (form in forms) assertEquals(form.name, form, words.formNamed(form.name))
    }

    @Test
    fun noTwoBundledFormsShareTheirStems() {
        val collisions = forms.groupBy { it.stems }.filterValues { it.size > 1 }.values.map { group -> group.map { it.name } }
        assertEquals(emptyList<List<String>>(), collisions)
    }
}

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
 * Встроенный производственный снимок содержит только короткий словарь сервера.
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
    fun bundledFormsAreTheEighteenServerBases() {
        assertEquals(
            setOf("таблетки", "раствор", "капсулы", "порошок", "капли", "лиофилизат",
                "концентрат", "мазь", "спрей", "гель", "суспензия", "крем", "сироп",
                "суппозитории", "гранулы", "аэрозоль", "настойка", "другие"),
            forms.map { it.name }.toSet()
        )
        val words = Vocabulary(emptyList(), forms)
        for (form in forms) assertEquals(form.name, form, words.formWithName(form.name))
    }
}

package com.kert0n.medapp.network.marking

import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Vocabulary
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Старые 208 названий — входные варианты реестра, а не список форм клиента. */
class MarkingFormMapperTest {
    private val mapping = listOf(
        File("src/test/resources/marking/detailed-form-mapping.tsv"),
        File("app/src/test/resources/marking/detailed-form-mapping.tsv")
    ).firstOrNull(File::isFile) ?: error("нет таблицы ожиданий для подробных форм")

    private val names = listOf(
        "таблетки", "раствор", "капсулы", "порошок", "капли", "лиофилизат",
        "концентрат", "мазь", "спрей", "гель", "суспензия", "крем", "сироп",
        "суппозитории", "гранулы", "аэрозоль", "настойка", "другие"
    )
    private val forms = names.mapIndexed { index, name ->
        DosageForm(Uuid.parse("00000000-0000-4000-8000-%012d".format(index + 1)), name)
    }
    private val words = Vocabulary(emptyList(), forms)

    @Test
    fun everyOldDetailedNameBecomesOneServerBaseForm() {
        val rows = mapping.readLines().map { it.split('\t') }
        assertEquals(208, rows.size)
        for ((raw, canonical) in rows) {
            assertEquals(raw, words.formWithName(canonical), MarkingFormMapper.resolve(raw, words))
        }
    }

    @Test
    fun registryWordingAndAbbreviationsKeepThePhysicalBase() {
        val examples = mapOf(
            "ТАБЛЕТКА, ПОКРЫТАЯ ПЛЁНОЧНОЙ ОБОЛОЧКОЙ, 10 мг" to "таблетки",
            "табл. п/пл/о" to "таблетки",
            "р-р д/в/в" to "раствор",
            "Р-Р В/М" to "раствор",
            "капс." to "капсулы",
            "гранулы для приготовления суспензии" to "гранулы",
            "пластырь/таблетки" to "другие",
            "капсулы/таблетки" to "капсулы"
        )
        for ((raw, canonical) in examples) {
            assertEquals(raw, words.formWithName(canonical), MarkingFormMapper.resolve(raw, words))
        }
    }

    /**
     * То же самое, но на **встроенном снимке боевого сервера**, а не на выдуманном словаре:
     * маппер называет имя, и это имя должно найтись у сервера. Опечатка в корне («суспенз» →
     * «суспенция») иначе даёт молчаливый `null`, и человек получает пустое поле формы вместо
     * подставленной.
     */
    @Test
    fun everyBaseTheMapperNamesExistsInTheBundledSnapshot() {
        val bundled = listOf(File("src/main/assets/vocabulary.json"), File("app/src/main/assets/vocabulary.json"))
            .firstOrNull(File::isFile) ?: error("встроенного снимка словаря нет")
        val snapshot = Vocabulary(
            emptyList(),
            Json.parseToJsonElement(bundled.readText()).jsonObject.getValue("formTypes").jsonArray.map {
                DosageForm(
                    Uuid.parse(it.jsonObject.getValue("id").jsonPrimitive.content),
                    it.jsonObject.getValue("name").jsonPrimitive.content
                )
            }
        )

        for ((raw, canonical) in mapping.readLines().map { it.split('\t') }) {
            val found = MarkingFormMapper.resolve(raw, snapshot)
            assertNotNull("«$raw» не нашлось во встроенном снимке", found)
            assertEquals(raw, canonical, found?.name)
        }
    }

    @Test
    fun unknownTextOrMissingServerBaseCreatesNoForm() {
        assertNull(MarkingFormMapper.resolve("неведомая форма", words))
        assertNull(MarkingFormMapper.resolve("т.", words))
        assertNull(MarkingFormMapper.resolve("   ", words))
        assertNull(MarkingFormMapper.resolve("пластырь", Vocabulary(emptyList(), forms.filter { it.name != "другие" })))
    }
}

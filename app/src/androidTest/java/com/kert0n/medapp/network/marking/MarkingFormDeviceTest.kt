package com.kert0n.medapp.network.marking

import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Vocabulary
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** На устройстве подсказка реестра маркировки выбирает id только из встроенного production-снимка сервера. */
class MarkingFormDeviceTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val forms = json.parseToJsonElement(
        InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("vocabulary.json").bufferedReader().use { it.readText() }
    ).jsonObject.getValue("formTypes").jsonArray.map { entry ->
        val item = entry.jsonObject
        DosageForm(Uuid.parse(item.getValue("id").jsonPrimitive.content), item.getValue("name").jsonPrimitive.content)
    }
    private val vocabulary = Vocabulary(emptyList(), forms)

    private fun suggestion(text: String) = json.decodeFromString(
        MarkingCheckNetworkDTO.serializer(),
        """{"codeFounded":true,"category":"drugs","screen":{"items":[{"pharmacyData":{"form":"$text"}}]}}"""
    ).toSuggestion(vocabulary, LocalDate.of(2026, 9, 17))

    @Test
    fun commonRareAndUnknownRegistryFormsKeepTheServerIdentityAndRawText() {
        assertEquals(18, forms.size)
        for ((raw, canonical) in listOf(
            "таблетки покрытые пленочной оболочкой" to "таблетки",
            "пластырь" to "другие"
        )) {
            val result = suggestion(raw)
            assertEquals(raw, result.formText)
            assertEquals(vocabulary.formWithName(canonical), result.form)
        }
        val unknown = suggestion("неведомая форма")
        assertEquals("неведомая форма", unknown.formText)
        assertNull(unknown.form)
    }
}

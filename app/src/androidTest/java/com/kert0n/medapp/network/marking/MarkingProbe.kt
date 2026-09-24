package com.kert0n.medapp.network.marking

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.BuildConfig
import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.server.markingHttpClient
import com.kert0n.medapp.network.server.markingJson
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Живой ответ реестра маркировки — **по одному запросу на код** за запуск и только по
 * `-PprobeMarking` (PLAN H5): сервис чужой, обращаемся бережно. Проба печатает **форму** ответа —
 * имена ключей без значений — и разрешённые поля (категория, название, аптечный блок, метки
 * атрибутов, страна); сырое тело, код, GTIN и серийный номер в отчёт не попадают ни в каком виде.
 * При «не найдено» или отказе проба не перебирает варианты, а называет ответ. Коды — из
 * `local.properties`, в git не попадают.
 */
class MarkingProbe {

    @Test
    fun theLiveAnswersReadIntoOurShape() = runBlocking {
        val encoded = InstrumentationRegistry.getArguments().getString("probeMarkingCodes")
        assumeTrue("проба маркировки включается только -PprobeMarking с MEDAPP_MARKING_PROBE_CODES", !encoded.isNullOrBlank())
        val codes = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).split(';').filter { it.isNotBlank() }.map(::DataMatrixCode)
        assumeTrue("список кодов пуст", codes.isNotEmpty())

        val asset = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("vocabulary.json").bufferedReader().use { it.readText() }
        val forms = markingJson.parseToJsonElement(asset).jsonObject.getValue("formTypes").jsonArray.map { entry ->
            val item = entry.jsonObject
            DosageForm(Uuid.parse(item.getValue("id").jsonPrimitive.content), item.getValue("name").jsonPrimitive.content)
        }
        val vocabulary = Vocabulary(emptyList(), forms)

        val client = markingHttpClient(OkHttp.create(), BuildConfig.MARKING_URL)
        for ((index, code) in codes.withIndex()) {
            val response = client.post {
                contentType(ContentType.Application.Json)
                setBody(MarkingCheckRequestNetworkDTO.of(code))
            }
            val raw = response.bodyAsText()
            println("MARKING_PROBE[$index] status=${response.status}")

            // 451 — доступ закрыт по месту. Это названный исход, а не провал пробы; форму ответа он
            // подтвердить не даёт.
            assertTrue(
                "код $index: статус ${response.status} — ни ответ, ни «не найдено», ни отказ по месту",
                response.status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound, HttpStatusCode.BadRequest, MarkingApi.UNAVAILABLE_FOR_LEGAL_REASONS)
            )
            if (response.status == HttpStatusCode.OK) {
                println("MARKING_PROBE[$index] shape=${shapeOf(markingJson.parseToJsonElement(raw))}")
                val dto = markingJson.decodeFromString(MarkingCheckNetworkDTO.serializer(), raw)
                println("MARKING_PROBE[$index] codeFounded=${dto.codeFounded} category=${dto.category} name=${dto.productName} expireDate=${dto.expireDate}")
                println("MARKING_PROBE[$index] pharmacy=${dto.pharmacy} labels=${dto.attributes.keys} country=${dto.chip("country")}")
                println("MARKING_PROBE[$index] suggestion=${dto.toSuggestion(vocabulary, LocalDate.now(MARKING_ZONE))}")
            }
        }
    }

    /** Имена ключей без значений: форму ответа видно, а идентификаторов и текстов в отчёте нет. */
    private fun shapeOf(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.joinToString(",", "{", "}") { (key, value) -> key + shapeOf(value).let { if (it.isEmpty()) "" else ":$it" } }
        is JsonArray -> element.firstOrNull()?.let { "[" + shapeOf(it) + "]" } ?: "[]"
        else -> ""
    }
}

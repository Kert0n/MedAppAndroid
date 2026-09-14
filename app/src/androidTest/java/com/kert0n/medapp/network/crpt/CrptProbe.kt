package com.kert0n.medapp.network.crpt

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.BuildConfig
import com.kert0n.medapp.domain.scan.DataMatrixCode
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.server.crptHttpClient
import com.kert0n.medapp.network.server.crptJson
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Живой ответ «Честного знака» — **по одному запросу на код** за запуск и только по `-PprobeCrpt`
 * (PLAN H5): чужой недокументированный API, обращаемся бережно. Проба печатает каждый ответ
 * (серийный номер заглушкой) и проверяет, что его форма читается нашим DTO; при «не найдено» или
 * отказе она не перебирает варианты, а называет ответ. Коды — из `local.properties`, в git не попадают.
 */
class CrptProbe {

    @Test
    fun theLiveAnswersReadIntoOurShape() = runBlocking {
        val encoded = InstrumentationRegistry.getArguments().getString("probeCrptCodes")
        assumeTrue("проба «Честного знака» включается только -PprobeCrpt с MEDAPP_CRPT_PROBE_CODES", !encoded.isNullOrBlank())
        val codes = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).split(';').filter { it.isNotBlank() }.map(::DataMatrixCode)
        assumeTrue("список кодов пуст", codes.isNotEmpty())

        val client = crptHttpClient(OkHttp.create(), BuildConfig.CRPT_BASE_URL)
        for ((index, code) in codes.withIndex()) {
            val response = client.post(CrptApi.CHECK) {
                contentType(ContentType.Application.Json)
                setBody(CrptCheckRequestNetworkDTO(code.wire, CrptCheckRequestNetworkDTO.DATA_MATRIX))
            }
            val raw = response.bodyAsText()
            // Серийный номер коробки в отчёт не попадает: ни как текст, ни как JSON-экранированный код.
            val shown = raw.replace(code.text, "<код>").replace(Regex("\"(code|serial)\":\"[^\"]*\""), "\"$1\":\"<скрыто>\"")
            println("CRPT_PROBE[$index] status=${response.status} body=$shown")

            // 451 — доступ закрыт по месту: из сети вне России реестр не отвечает. Это названный
            // исход, а не провал пробы; форму ответа он подтвердить не даёт.
            assertTrue(
                "код $index: статус ${response.status} — ни ответ, ни «не найдено», ни отказ по месту: $shown",
                response.status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound, HttpStatusCode.BadRequest, CrptApi.UNAVAILABLE_FOR_LEGAL_REASONS)
            )
            if (response.status == HttpStatusCode.OK) {
                val dto = crptJson.decodeFromString(CrptCheckNetworkDTO.serializer(), raw)
                println("CRPT_PROBE[$index] codeFounded=${dto.codeFounded} category=${dto.category} name=${dto.productName} expireDate=${dto.expireDate}")
                println("CRPT_PROBE[$index] pharmacy=${dto.pharmacy} labels=${dto.attributes.keys} country=${dto.chip("country")}")
                println("CRPT_PROBE[$index] suggestion=${dto.toSuggestion(Vocabulary.empty)}")
            }
        }
    }
}

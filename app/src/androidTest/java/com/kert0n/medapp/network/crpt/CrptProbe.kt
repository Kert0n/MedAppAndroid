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
 * Живой ответ «Честного знака» — **один запрос** за запуск и только по `-PprobeCrpt` (PLAN H5):
 * чужой недокументированный API, обращаемся бережно. Проба печатает ответ (серийный номер
 * заглушкой) и проверяет, что его форма читается нашим DTO; при «не найдено» или отказе она не
 * перебирает варианты, а называет ответ. Код — из `local.properties`, в git не попадает.
 */
class CrptProbe {

    @Test
    fun theLiveAnswerReadsIntoOurShape() = runBlocking {
        val encoded = InstrumentationRegistry.getArguments().getString("probeCrptCode")
        assumeTrue("проба «Честного знака» включается только -PprobeCrpt с MEDAPP_CRPT_PROBE_CODE", !encoded.isNullOrBlank())
        val code = DataMatrixCode(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))

        val client = crptHttpClient(OkHttp.create(), BuildConfig.CRPT_BASE_URL)
        val response = client.post(CrptApi.CHECK) {
            contentType(ContentType.Application.Json)
            setBody(CrptCheckRequestNetworkDTO(code.wire, CrptCheckRequestNetworkDTO.DATA_MATRIX))
        }
        val raw = response.bodyAsText()
        val shown = raw.replace(code.text, "<код>")
        println("CRPT_PROBE status=${response.status} body=$shown")

        // 451 — доступ закрыт по месту: из сети вне России реестр не отвечает. Это названный
        // исход, а не провал пробы; форму ответа он подтвердить не даёт.
        assertTrue(
            "статус ${response.status} — ни ответ, ни «не найдено», ни отказ по месту: $shown",
            response.status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound, HttpStatusCode.BadRequest, CrptApi.UNAVAILABLE_FOR_LEGAL_REASONS)
        )
        if (response.status == HttpStatusCode.OK) {
            val dto = crptJson.decodeFromString(CrptCheckNetworkDTO.serializer(), raw)
            println("CRPT_PROBE codeFounded=${dto.codeFounded} category=${dto.category} name=${dto.productName} expireDate=${dto.expireDate}")
            println("CRPT_PROBE pharmacy=${dto.pharmacy} labels=${dto.attributes.keys}")
            println("CRPT_PROBE suggestion=${dto.toSuggestion(Vocabulary.empty)}")
        }
    }
}

package com.kert0n.medapp.network.crpt

import com.kert0n.medapp.di.CrptHttp
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.scan.DataMatrixCode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Один вопрос «Честному знаку»: что за код (PLAN H5). Клиент отдельный и без пропуска MedApp
 * (G3). Исходы — те, что различает сценарий: тело ответа, «не найдено», недоступность. `404` и
 * `400` — тоже «не найдено»: так отвечает и наблюдаемый API, и референс; `451` и `403` — нас не
 * приняли (доступ закрыт по месту или правилу, и повтор тем же не поможет — так реестр ответил
 * пробе из сети вне России); прочие статусы и нечитаемое тело — «сервер
 * промолчал», обрыв — «связи нет».
 */
class CrptApi @Inject constructor(@CrptHttp private val client: HttpClient) {

    suspend fun check(code: DataMatrixCode): CrptCheck = try {
        val response = client.post(CHECK) {
            contentType(ContentType.Application.Json)
            setBody(CrptCheckRequestNetworkDTO(code.wire, CrptCheckRequestNetworkDTO.DATA_MATRIX))
        }
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = runCatching { response.body<CrptCheckNetworkDTO>() }.getOrNull()
                    ?: return CrptCheck.Unavailable(Unavailability.SERVER_SILENT)
                if (body.codeFounded) CrptCheck.Body(body) else CrptCheck.NotFound
            }
            HttpStatusCode.NotFound, HttpStatusCode.BadRequest -> CrptCheck.NotFound
            UNAVAILABLE_FOR_LEGAL_REASONS, HttpStatusCode.Forbidden -> CrptCheck.Unavailable(Unavailability.SERVER_REFUSED_US)
            else -> CrptCheck.Unavailable(Unavailability.SERVER_SILENT)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        CrptCheck.Unavailable(Unavailability.NO_CONNECTION)
    }

    companion object {
        const val CHECK = "/v2/mobile/check"

        /** 451 Unavailable For Legal Reasons — в Ktor не именован. */
        val UNAVAILABLE_FOR_LEGAL_REASONS = HttpStatusCode(451, "Unavailable For Legal Reasons")
    }
}

/** Чем кончился вопрос — ровно те случаи, с которыми дальше поступают по-разному. */
sealed interface CrptCheck {

    data class Body(val dto: CrptCheckNetworkDTO) : CrptCheck

    data object NotFound : CrptCheck

    data class Unavailable(val reason: Unavailability) : CrptCheck
}

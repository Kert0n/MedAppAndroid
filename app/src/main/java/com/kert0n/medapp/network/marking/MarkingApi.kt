package com.kert0n.medapp.network.marking

import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.di.MarkingHttp
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
 * Один вопрос реестру маркировки: что за код (PLAN H5). Клиент отдельный и без пропуска MedApp
 * (G3), адрес проверки — его целиком. Исходы — те, что различает сценарий: тело ответа, «не
 * найдено», недоступность. `404` и `400` — тоже «не найдено»; `451` и `403` — нас не приняли (доступ
 * закрыт по месту или правилу, и повтор тем же не поможет); прочие статусы и нечитаемое тело —
 * «сервер промолчал», обрыв — «связи нет».
 */
class MarkingApi @Inject constructor(@MarkingHttp private val client: HttpClient) {

    suspend fun check(code: DataMatrixCode): MarkingCheck = try {
        val response = client.post {
            contentType(ContentType.Application.Json)
            setBody(MarkingCheckRequestNetworkDTO.of(code))
        }
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = attempt { response.body<MarkingCheckNetworkDTO>() }.getOrNull()
                    ?: return MarkingCheck.Unavailable(Unavailability.SERVER_SILENT)
                if (body.codeFounded) MarkingCheck.Body(body) else MarkingCheck.NotFound
            }
            HttpStatusCode.NotFound, HttpStatusCode.BadRequest -> MarkingCheck.NotFound
            UNAVAILABLE_FOR_LEGAL_REASONS, HttpStatusCode.Forbidden -> MarkingCheck.Unavailable(Unavailability.SERVER_REFUSED_US)
            else -> MarkingCheck.Unavailable(Unavailability.SERVER_SILENT)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        MarkingCheck.Unavailable(Unavailability.NO_CONNECTION)
    }

    companion object {
        /** 451 Unavailable For Legal Reasons — в Ktor не именован. */
        val UNAVAILABLE_FOR_LEGAL_REASONS = HttpStatusCode(451, "Unavailable For Legal Reasons")
    }
}

/** Чем кончился вопрос — ровно те случаи, с которыми дальше поступают по-разному. */
sealed interface MarkingCheck {

    data class Body(val dto: MarkingCheckNetworkDTO) : MarkingCheck

    data object NotFound : MarkingCheck

    data class Unavailable(val reason: Unavailability) : MarkingCheck
}

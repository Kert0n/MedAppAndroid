package com.kert0n.medapp.network.server

import com.kert0n.medapp.di.MedAppHttp
import com.kert0n.medapp.network.account.AccessTokenNetworkDTO
import com.kert0n.medapp.network.account.AccessTokenThrottled
import com.kert0n.medapp.network.account.AccessTokenUnavailable
import com.kert0n.medapp.domain.account.AccountCredentials
import com.kert0n.medapp.network.account.AccountPostNetworkDTO
import com.kert0n.medapp.network.account.AccountSnapshotNetworkDTO
import com.kert0n.medapp.network.medkit.InvitationNetworkDTO
import com.kert0n.medapp.network.medkit.MedKitCreatedNetworkDTO
import com.kert0n.medapp.network.medkit.MedKitNetworkDTO
import com.kert0n.medapp.network.medkit.MedKitPostNetworkDTO
import com.kert0n.medapp.network.medkit.MedKitSummaryNetworkDTO
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.pack.ClaimNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPatchNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPostNetworkDTO
import com.kert0n.medapp.network.pack.PackagePatchNetworkDTO
import com.kert0n.medapp.network.pack.PackagePostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import com.kert0n.medapp.network.template.PackageTemplateNetworkDTO
import com.kert0n.medapp.network.value.VocabularyEntryNetworkDTO
import com.kert0n.medapp.queue.ResourceVersion
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Все 26 операций сервера MedApp (PLAN B4). У каждой объявлено, что считается успехом: статус и
 * политика тела — обязательный JSON, JSON или ноль байтов (пачка кончилась и уничтожена), либо
 * отсутствие тела у `204` (PLAN B5). Всё остальное — [ApiFailure], а не исключение.
 *
 * Изменяющая команда и чтение различаются исходом сбоя: у чтения ничего не применено, у
 * команды исход неизвестен, и её вслепую не повторяют (PLAN E3).
 */
@Singleton
class MedAppApi @Inject constructor(@MedAppHttp private val http: HttpClient) {

    // Учётная запись

    /**
     * Учётные данные придумывает клиент, поэтому повтор безопасен: второй учётки он не заводит, а
     * отвечает `409` по занятому логину (PLAN B1). Тела в ответе нет.
     */
    suspend fun register(account: AccountCredentials, registrationToken: String): ApiResult<Unit> =
        call(HttpMethod.Post, MedAppRoutes.REGISTER, HttpStatusCode.Created, none) {
            header(REGISTRATION_TOKEN_HEADER, registrationToken)
            json(AccountPostNetworkDTO(account.login, account.password))
        }

    /** Состояния сервера не меняет: неудача значит «пропуска нет», а не «исход неизвестен». */
    suspend fun token(credentials: AccountCredentials): ApiResult<AccessTokenNetworkDTO> =
        call(
            HttpMethod.Post,
            MedAppRoutes.TOKEN,
            HttpStatusCode.OK,
            required(AccessTokenNetworkDTO.serializer()),
            command = false
        ) {
            basicAuth(credentials.login.toString(), credentials.password)
        }

    // Снимок и словари

    suspend fun snapshot(): ApiResult<AccountSnapshotNetworkDTO> =
        call(HttpMethod.Get, MedAppRoutes.ME, HttpStatusCode.OK, required(AccountSnapshotNetworkDTO.serializer()))

    suspend fun medKits(): ApiResult<List<MedKitSummaryNetworkDTO>> =
        call(HttpMethod.Get, MedAppRoutes.MED_KITS, HttpStatusCode.OK, required(ListSerializer(MedKitSummaryNetworkDTO.serializer())))

    suspend fun medKit(medKitId: Uuid): ApiResult<MedKitNetworkDTO> =
        call(HttpMethod.Get, MedAppRoutes.medKit(medKitId), HttpStatusCode.OK, required(MedKitNetworkDTO.serializer()))

    suspend fun quantityUnits(): ApiResult<List<VocabularyEntryNetworkDTO>> =
        call(HttpMethod.Get, MedAppRoutes.QUANTITY_UNITS, HttpStatusCode.OK, required(ListSerializer(VocabularyEntryNetworkDTO.serializer())))

    suspend fun formTypes(): ApiResult<List<VocabularyEntryNetworkDTO>> =
        call(HttpMethod.Get, MedAppRoutes.FORM_TYPES, HttpStatusCode.OK, required(ListSerializer(VocabularyEntryNetworkDTO.serializer())))

    // Аптечки

    suspend fun createMedKit(medKit: MedKitPostNetworkDTO): ApiResult<MedKitCreatedNetworkDTO> =
        call(HttpMethod.Post, MedAppRoutes.MED_KITS, HttpStatusCode.Created, required(MedKitCreatedNetworkDTO.serializer())) {
            json(medKit)
        }

    /** Удаляет аптечку у всех; [transferTo] переносит содержимое в другую аптечку вызывающего. */
    suspend fun deleteMedKit(medKitId: Uuid, transferTo: Uuid? = null): ApiResult<Unit> =
        call(HttpMethod.Delete, MedAppRoutes.medKit(medKitId), HttpStatusCode.NoContent, none) {
            transferTo?.let { parameter("targetMedKitId", it.toString()) }
        }

    suspend fun createInvitation(medKitId: Uuid): ApiResult<InvitationNetworkDTO> =
        call(HttpMethod.Post, MedAppRoutes.invitations(medKitId), HttpStatusCode.Created, required(InvitationNetworkDTO.serializer()))

    suspend fun joinMedKit(membership: MembershipPostNetworkDTO): ApiResult<MedKitNetworkDTO> =
        call(HttpMethod.Post, MedAppRoutes.MEMBERSHIPS, HttpStatusCode.Created, required(MedKitNetworkDTO.serializer())) {
            json(membership)
        }

    suspend fun leaveMedKit(medKitId: Uuid): ApiResult<Unit> =
        call(HttpMethod.Delete, MedAppRoutes.membership(medKitId), HttpStatusCode.NoContent, none)

    // Упаковки

    suspend fun createPackage(medKitId: Uuid, pack: PackagePostNetworkDTO): ApiResult<PackageSnapshotNetworkDTO> =
        call(HttpMethod.Post, MedAppRoutes.packagesOf(medKitId), HttpStatusCode.Created, required(PackageSnapshotNetworkDTO.serializer())) {
            json(pack)
        }

    suspend fun packageSnapshot(packageId: Uuid): ApiResult<PackageSnapshotNetworkDTO> =
        call(HttpMethod.Get, MedAppRoutes.pack(packageId), HttpStatusCode.OK, required(PackageSnapshotNetworkDTO.serializer()))

    suspend fun patchPackage(packageId: Uuid, patch: PackagePatchNetworkDTO): ApiResult<PackageSnapshotNetworkDTO> =
        call(HttpMethod.Patch, MedAppRoutes.pack(packageId), HttpStatusCode.OK, required(PackageSnapshotNetworkDTO.serializer())) {
            json(patch)
        }

    suspend fun deletePackage(packageId: Uuid, version: ResourceVersion?): ApiResult<Unit> =
        call(HttpMethod.Delete, MedAppRoutes.pack(packageId), HttpStatusCode.NoContent, none) {
            version(version)
        }

    suspend fun movePackage(
        packageId: Uuid,
        targetMedKitId: Uuid,
        version: ResourceVersion?
    ): ApiResult<PackageSnapshotNetworkDTO> =
        call(HttpMethod.Put, MedAppRoutes.packageIn(targetMedKitId, packageId), HttpStatusCode.OK, required(PackageSnapshotNetworkDTO.serializer())) {
            version(version)
        }

    /** `null` в успехе — пачка кончилась и уничтожена; повтор безопасен с тем же [syncId]. */
    suspend fun synchronise(
        packageId: Uuid,
        syncId: Uuid,
        changes: PackageSyncNetworkDTO
    ): ApiResult<PackageSnapshotNetworkDTO?> =
        call(HttpMethod.Put, MedAppRoutes.sync(packageId, syncId), HttpStatusCode.OK, optional(PackageSnapshotNetworkDTO.serializer())) {
            json(changes)
        }

    // Брони

    suspend fun claims(): ApiResult<List<ClaimNetworkDTO>> =
        call(HttpMethod.Get, MedAppRoutes.CLAIMS, HttpStatusCode.OK, required(ListSerializer(ClaimNetworkDTO.serializer())))

    suspend fun claim(packageId: Uuid): ApiResult<ClaimNetworkDTO> =
        call(HttpMethod.Get, MedAppRoutes.claim(packageId), HttpStatusCode.OK, required(ClaimNetworkDTO.serializer()))

    suspend fun createClaim(claim: ClaimPostNetworkDTO): ApiResult<ClaimNetworkDTO> =
        call(HttpMethod.Post, MedAppRoutes.CLAIMS, HttpStatusCode.Created, required(ClaimNetworkDTO.serializer())) {
            json(claim)
        }

    suspend fun patchClaim(packageId: Uuid, claim: ClaimPatchNetworkDTO): ApiResult<ClaimNetworkDTO> =
        call(HttpMethod.Patch, MedAppRoutes.claim(packageId), HttpStatusCode.OK, required(ClaimNetworkDTO.serializer())) {
            json(claim)
        }

    suspend fun deleteClaim(packageId: Uuid, version: ResourceVersion?): ApiResult<Unit> =
        call(HttpMethod.Delete, MedAppRoutes.claim(packageId), HttpStatusCode.NoContent, none) {
            version(version)
        }

    // Справочник

    suspend fun searchTemplates(query: String, limit: Int): ApiResult<List<PackageTemplateNetworkDTO>> {
        require(query.length in 1..TEMPLATE_QUERY_MAX) { "запрос справочника — от 1 до $TEMPLATE_QUERY_MAX символов" }
        require(limit in 1..TEMPLATE_LIMIT_MAX) { "справочник отдаёт от 1 до $TEMPLATE_LIMIT_MAX карточек" }
        return call(HttpMethod.Get, MedAppRoutes.TEMPLATES, HttpStatusCode.OK, required(ListSerializer(PackageTemplateNetworkDTO.serializer()))) {
            parameter("query", query)
            parameter("limit", limit)
        }
    }

    suspend fun template(templateId: Uuid): ApiResult<PackageTemplateNetworkDTO> =
        call(HttpMethod.Get, MedAppRoutes.template(templateId), HttpStatusCode.OK, required(PackageTemplateNetworkDTO.serializer()))

    // Очередь

    /**
     * Готовый изменяющий запрос как есть — примитивами: метод, путь, параметры и тело собрала
     * очередь, и здесь они не пересобираются (PLAN E2). Успех — любой 2xx со статусом и телом
     * строкой: какая форма за ним стоит и какой статус ожидался, знает та команда, что запрос
     * готовила, — сеть про очередь не знает.
     */
    suspend fun send(method: String, path: String, query: Map<String, String>, body: String?): ApiResult<RawResponse> = try {
        val response = http.request(path) {
            this.method = HttpMethod.parse(method)
            query.forEach { (name, value) -> parameter(name, value) }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        when {
            response.status.isSuccess() -> ApiResult.Success(RawResponse(response.status.value, response.bodyAsText()))
            else -> ApiResult.Failure(refusal(response, command = true, path = path))
        }
    } catch (cause: AccessTokenThrottled) {
        ApiResult.Failure(ApiFailure.TooManyRequests(cause.retryAfter))
    } catch (_: AccessTokenUnavailable) {
        ApiResult.Failure(ApiFailure.Unavailable)
    } catch (broken: IOException) {
        ApiResult.Failure(broken.asFailure(command = true))
    }

    // Исполнение

    /**
     * [command] объявляет операция, а не метод: изменяют состояние сервера все не-`GET`, кроме
     * выдачи пропуска. Из этого признака следует исход сбоя — «ничего не применено» или
     * «исход неизвестен» (PLAN E3).
     */
    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        success: HttpStatusCode,
        reader: Reader<T>,
        command: Boolean = method != HttpMethod.Get,
        configure: HttpRequestBuilder.() -> Unit = {}
    ): ApiResult<T> {
        return try {
            val response = http.request(path) {
                this.method = method
                configure()
            }
            when {
                response.status == success -> reader(response, command)
                response.status.isSuccess() ->
                    broken(command, "успех ${response.status.value} вместо ${success.value}")
                else -> ApiResult.Failure(refusal(response, command, path))
            }
        } catch (cause: AccessTokenThrottled) {
            ApiResult.Failure(ApiFailure.TooManyRequests(cause.retryAfter))
        } catch (_: AccessTokenUnavailable) {
            ApiResult.Failure(ApiFailure.Unavailable)
        } catch (broken: IOException) {
            ApiResult.Failure(broken.asFailure(command))
        }
    }

    /**
     * Обрыв до сервера — адрес не разрешился, соединение не установилось, рукопожатие TLS не
     * прошло — не потерянный ответ: запрос никуда не ушёл, и у команды нет неизвестного исхода,
     * есть отсутствие связи. Обрыв после — исход неизвестен (PLAN E3).
     */
    private fun IOException.asFailure(command: Boolean): ApiFailure = when {
        !command -> ApiFailure.Unavailable
        this is UnknownHostException || this is ConnectException || this is java.net.NoRouteToHostException ||
            this is ConnectTimeoutException || this is SSLHandshakeException -> ApiFailure.Unavailable
        else -> ApiFailure.OutcomeUnknown
    }

    /** Отказ сервера — решение по коду ответа (PLAN B5); тело добавляет только `errors[]` при 400. */
    private suspend fun refusal(response: HttpResponse, command: Boolean, path: String): ApiFailure =
        when (response.status.value) {
            400 -> ApiFailure.Invalid(
                problem(response).errors.map { ApiFailure.FieldError(it.field, it.reason) }
            )
            401 -> ApiFailure.Unauthorized
            403 ->
                if (path == MedAppRoutes.REGISTER) ApiFailure.RegistrationRefused
                else ApiFailure.Protocol("403 вне регистрации")
            404 -> ApiFailure.NotFound
            409 -> ApiFailure.Conflict
            412 -> ApiFailure.PreconditionFailed
            428 -> ApiFailure.PreconditionRequired
            429 -> ApiFailure.TooManyRequests(response.retryAfter())
            in 500..599 -> if (command) ApiFailure.OutcomeUnknown else ApiFailure.Unavailable
            else -> ApiFailure.Protocol("отказ ${response.status.value} вне контракта")
        }

    private suspend fun problem(response: HttpResponse): ProblemNetworkDTO = try {
        problemJson.decodeFromString(ProblemNetworkDTO.serializer(), response.bodyAsText())
    } catch (_: IllegalArgumentException) {
        ProblemNetworkDTO()
    }

    private fun <T> required(serializer: KSerializer<T>): Reader<T> = { response, command ->
        val text = response.bodyAsText()
        if (text.isEmpty()) broken(command, "пустое тело там, где контракт обещает JSON")
        else decode(serializer, text, command)
    }

    private fun <T : Any> optional(serializer: KSerializer<T>): Reader<T?> = { response, command ->
        val text = response.bodyAsText()
        if (text.isEmpty()) ApiResult.Success(null) else decode(serializer, text, command)
    }

    private val none: Reader<Unit> = { _, _ -> ApiResult.Success(Unit) }

    private fun <T> decode(serializer: KSerializer<T>, text: String, command: Boolean): ApiResult<T> =
        try {
            ApiResult.Success(medAppJson.decodeFromString(serializer, text))
        } catch (_: IllegalArgumentException) {
            broken(command, "тело ответа не по контракту")
        }

    /** Ответ не по контракту: у чтения это ошибка протокола, у команды — неизвестный исход. */
    private fun broken(command: Boolean, reason: String): ApiResult<Nothing> =
        ApiResult.Failure(if (command) ApiFailure.OutcomeUnknown else ApiFailure.Protocol(reason))

    private inline fun <reified B> HttpRequestBuilder.json(body: B) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.version(version: ResourceVersion?) {
        version?.let { parameter("version", it.number) }
    }

    private companion object {
        const val TEMPLATE_QUERY_MAX = 200
        const val TEMPLATE_LIMIT_MAX = 50
    }
}

/** Как операция читает свой успешный ответ; второй аргумент — изменяет ли она состояние сервера. */
private typealias Reader<T> = suspend (HttpResponse, Boolean) -> ApiResult<T>

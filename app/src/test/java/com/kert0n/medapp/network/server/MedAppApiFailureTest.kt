package com.kert0n.medapp.network.server

import com.kert0n.medapp.network.account.AccountCredentials
import com.kert0n.medapp.network.medkit.MedKitPostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import com.kert0n.medapp.network.pack.PackagePatchNetworkDTO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отказ сервера становится решением по коду ответа (PLAN B5): код и есть код ошибки, а тело
 * добавляет только `errors[]` при 400.
 */
class MedAppApiFailureTest {

    private val kit = Uuid.parse("00000000-0000-4000-8000-000000000021")
    private val pack = Uuid.parse("00000000-0000-4000-8000-000000000011")

    private val sync = Uuid.parse("00000000-0000-4000-8000-000000000051")

    private fun api(
        status: HttpStatusCode,
        body: String = "",
        headers: List<Pair<String, String>> = emptyList()
    ) = MedAppApi(
        medAppHttpClient(
            MockEngine {
                respond(
                    body,
                    status,
                    headersOf(
                        *(headers + (HttpHeaders.ContentType to "application/problem+json"))
                            .map { it.first to listOf(it.second) }
                            .toTypedArray()
                    )
                )
            },
            "https://medapp.test",
            retryDelay = { delayMillis(false) { 0L } }
        )
    )

    private fun failureOf(result: ApiResult<*>): ApiFailure = (result as ApiResult.Failure).failure

    @Test
    fun badRequestNamesTheFieldsAndWhatIsWrong() = runTest {
        val body = """
            {"type":"about:blank","title":"Bad Request","status":400,
             "detail":"Validation failed","instance":"/v1/drugs/$pack/sync/$sync",
             "errors":[{"field":"quantity","reason":"must be greater than zero"}]}
        """

        val failure = failureOf(
            api(HttpStatusCode.BadRequest, body).synchronise(pack, sync, PackageSyncNetworkDTO("2", ResourceVersionNetworkDTO(3)))
        )

        assertEquals(
            ApiFailure.Invalid(listOf(ApiFailure.FieldError("quantity", "must be greater than zero"))),
            failure
        )
    }

    /** Сломанное тело отказа не отнимает решения, которое уже дал статус. */
    @Test
    fun badRequestWithUnreadableProblemIsStillInvalid() = runTest {
        val failure = failureOf(
            api(HttpStatusCode.BadRequest, "<html>").synchronise(pack, sync, PackageSyncNetworkDTO("2", ResourceVersionNetworkDTO(3)))
        )

        assertEquals(ApiFailure.Invalid(emptyList()), failure)
    }

    @Test
    fun unauthorizedMeansTheAccountIsNotAccepted() = runTest {
        assertEquals(ApiFailure.Unauthorized, failureOf(api(HttpStatusCode.Unauthorized).snapshot()))
    }

    @Test
    fun forbiddenRegistrationIsAConfigurationErrorNotANewAccount() = runTest {
        assertEquals(
            ApiFailure.RegistrationRefused,
            failureOf(api(HttpStatusCode.Forbidden).register(AccountCredentials.random(), "wrong-token"))
        )
    }

    @Test
    fun forbiddenOutsideRegistrationIsNotInTheContract() = runTest {
        assertTrue(failureOf(api(HttpStatusCode.Forbidden).snapshot()) is ApiFailure.Protocol)
    }

    @Test
    fun notFoundDoesNotSayWhy() = runTest {
        assertEquals(ApiFailure.NotFound, failureOf(api(HttpStatusCode.NotFound).packageSnapshot(pack)))
    }

    /** Повтор создания с тем же `id` — занятый идентификатор, а не вторая аптечка. */
    @Test
    fun repeatedCreationWithTheSameIdIsAConflict() = runTest {
        assertEquals(
            ApiFailure.Conflict,
            failureOf(api(HttpStatusCode.Conflict).createMedKit(MedKitPostNetworkDTO(kit)))
        )
    }

    @Test
    fun staleVersionIsAFailedPrecondition() = runTest {
        val result = api(HttpStatusCode.PreconditionFailed)
            .patchPackage(pack, PackagePatchNetworkDTO(name = "Аспирин C", version = ResourceVersionNetworkDTO(2)))

        assertEquals(ApiFailure.PreconditionFailed, failureOf(result))
    }

    @Test
    fun missingVersionIsARequiredPrecondition() = runTest {
        val result = api(HttpStatusCode(428, "Precondition Required"))
            .patchPackage(pack, PackagePatchNetworkDTO(name = "Аспирин C"))

        assertEquals(ApiFailure.PreconditionRequired, failureOf(result))
    }

    @Test
    fun throttlingCarriesRetryAfter() = runTest {
        val result = api(HttpStatusCode.TooManyRequests, headers = listOf(HttpHeaders.RetryAfter to "30"))
            .token(AccountCredentials(kit, "k"))

        assertEquals(ApiFailure.TooManyRequests(30.seconds), failureOf(result))
    }

    @Test
    fun throttlingWithoutUsableRetryAfterLeavesTheDelayToTheCaller() = runTest {
        val dated = api(
            HttpStatusCode.TooManyRequests,
            headers = listOf(HttpHeaders.RetryAfter to "Wed, 21 Oct 2026 07:28:00 GMT")
        ).snapshot()

        assertEquals(ApiFailure.TooManyRequests(null), failureOf(dated))
        assertEquals(ApiFailure.TooManyRequests(null), failureOf(api(HttpStatusCode.TooManyRequests).snapshot()))
    }

    /**
     * Признак изменения объявляет операция, а не метод: выдача пропуска — тоже `POST`, но
     * состояния сервера она не меняет, и её сбой значит «ничего не применено».
     */
    @Test
    fun aFailedTokenIssueIsUnavailableNotUnknown() = runTest {
        val down = HttpStatusCode.ServiceUnavailable

        assertEquals(ApiFailure.Unavailable, failureOf(api(down).token(AccountCredentials(kit, "k"))))
        assertEquals(
            ApiFailure.OutcomeUnknown,
            failureOf(api(down).createMedKit(MedKitPostNetworkDTO(kit)))
        )
    }

    @Test
    fun refusalOutsideTheContractIsAProtocolError() = runTest {
        val failure = failureOf(api(HttpStatusCode.MethodNotAllowed).leaveMedKit(kit))

        assertTrue(failure is ApiFailure.Protocol)
    }
}

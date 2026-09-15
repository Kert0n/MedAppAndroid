package com.kert0n.medapp.network.account

import com.kert0n.medapp.network.account.AccessTokens
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.REGISTRATION_TOKEN_HEADER
import com.kert0n.medapp.network.server.medAppHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Учётные данные придумывает устройство и записывает их до запроса, поэтому потерянный ответ
 * повторяется теми же данными и второй учётки не заводит (PLAN B1, G2). Нечитаемую поверх не
 * перерегистрируют, а одновременные вызовы дают одну регистрацию.
 */
class AccountRegistrationTest {

    private val login = Uuid.parse("00000000-0000-4000-8000-000000000071")

    private val password = "8oz6xk1uQm2Z0pA9fW3rT7cJ5nB4vL6yE8sD0gH2kM4"

    private class Memory(
        var account: StoredAccount,
        private val writable: Boolean = true
    ) : CredentialSource {
        override suspend fun read(): StoredAccount = account
        override suspend fun save(credentials: AccountCredentials): CredentialsSaved {
            if (!writable) return CredentialsSaved.LOST
            account = StoredAccount.Pending(credentials)
            return CredentialsSaved.SAVED
        }

        override suspend fun confirm(): CredentialsSaved {
            (account as? StoredAccount.Pending)?.let { account = StoredAccount.Present(it.credentials) }
            return CredentialsSaved.SAVED
        }

        var forgotten = 0
        override suspend fun forget(): CredentialsSaved {
            if (!writable) return CredentialsSaved.LOST
            forgotten++
            account = StoredAccount.Absent
            return CredentialsSaved.SAVED
        }
    }

    private val requests = mutableListOf<String>()

    private fun registration(
        stored: Memory,
        register: HttpStatusCode = HttpStatusCode.Created,
        token: HttpStatusCode = HttpStatusCode.OK
    ) = AccountRegistration(
        MedAppApi(
            medAppHttpClient(
                MockEngine { request ->
                    requests += "${request.method.value} ${request.url.encodedPath}"
                    when (request.url.encodedPath) {
                        "/v1/auth/register" -> {
                            registrationTokens += request.headers[REGISTRATION_TOKEN_HEADER]
                            bodies += request.bodyText()
                            respond("", register, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        else -> respond(
                            if (token == HttpStatusCode.OK) """{"accessToken":"t"}""" else "",
                            token,
                            headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                },
                "https://medapp.test"
            )
        ),
        stored,
        registrationToken = "build-token",
        tokens = AccessTokens(stored)
    )

    private val registrationTokens = mutableListOf<String?>()

    private val bodies = mutableListOf<String>()

    private suspend fun HttpRequestData.bodyText(): String =
        (body as io.ktor.http.content.TextContent).text

    /** Данные придуманы и записаны раньше запроса, а подтверждение приходит после ответа сервера. */
    @Test
    fun absentAccountIsInventedStoredAndThenRegistered() = runTest {
        val stored = Memory(StoredAccount.Absent)

        assertEquals(AccountRegistration.Outcome.Ready, registration(stored).ensure())

        val account = (stored.account as StoredAccount.Present).credentials
        assertTrue("пароль уехал на сервер", bodies.single().contains(account.password))
        assertEquals(listOf<String?>("build-token"), registrationTokens)
        assertEquals(listOf("POST /v1/auth/register"), requests)
    }

    /**
     * Ответ регистрации потерян: данные уже на устройстве, и повтор идёт **теми же**. Сервер
     * отвечает «логин занят», а пропуск по тем же данным показывает, что учётка наша.
     */
    @Test
    fun lostAnswerIsRepeatedWithTheVerySameCredentials() = runTest {
        val kept = AccountCredentials(login, password)
        val stored = Memory(StoredAccount.Pending(kept))

        val outcome = registration(stored, register = HttpStatusCode.Conflict).ensure()

        assertEquals(AccountRegistration.Outcome.Ready, outcome)
        assertEquals(StoredAccount.Present(kept), stored.account)
        assertTrue("повтор той же учёткой", bodies.single().contains("$login"))
        assertEquals(listOf("POST /v1/auth/register", "POST /v1/auth/token"), requests)
    }

    /** Решение человека о нечитаемой учётке: сохранённое стирается, и знакомство идёт заново новыми данными. */
    @Test
    fun replacingTheUnreadableForgetsItAndRegistersAnew() = runTest {
        val stored = Memory(StoredAccount.Unreadable)

        assertEquals(AccountRegistration.Outcome.Ready, registration(stored).replaceUnreadable())

        assertEquals(1, stored.forgotten)
        val account = (stored.account as StoredAccount.Present).credentials
        assertTrue("новые данные уехали на сервер", bodies.single().contains("${account.login}"))
        assertEquals(listOf("POST /v1/auth/register"), requests)
    }

    /**
     * Повтор решения после потерянного ответа: придуманное уже на устройстве, стирать его нельзя —
     * сервер, возможно, его знает, — и повтор идёт **теми же** данными, а не третьими.
     */
    @Test
    fun repeatingTheDecisionAfterALostAnswerKeepsTheInventedCredentials() = runTest {
        val kept = AccountCredentials(login, password)
        val stored = Memory(StoredAccount.Pending(kept))

        val outcome = registration(stored, register = HttpStatusCode.Conflict).replaceUnreadable()

        assertEquals(AccountRegistration.Outcome.Ready, outcome)
        assertEquals(0, stored.forgotten)
        assertEquals(StoredAccount.Present(kept), stored.account)
        assertTrue("повтор той же учёткой", bodies.single().contains("$login"))
    }

    /** Читаемую учётку решение не трогает: ни стирания, ни запроса. */
    @Test
    fun aReadableAccountIsNotReplaced() = runTest {
        val kept = AccountCredentials(login, password)
        val stored = Memory(StoredAccount.Present(kept))

        assertEquals(AccountRegistration.Outcome.Ready, registration(stored).replaceUnreadable())

        assertEquals(0, stored.forgotten)
        assertEquals(StoredAccount.Present(kept), stored.account)
        assertEquals(emptyList<String>(), requests)
    }

    /** Стереть не удалось — на устройстве по-прежнему нечитаемое, и завести поверх него нечего. */
    @Test
    fun anUnreadableThatCannotBeForgottenIsNotStored() = runTest {
        val stored = Memory(StoredAccount.Unreadable, writable = false)

        assertEquals(AccountRegistration.Outcome.NotStored, registration(stored).replaceUnreadable())

        assertEquals(emptyList<String>(), requests)
    }

    /** Логин занят чужой учёткой: пропуск не выдан, и новых данных поверх не придумывают. */
    @Test
    fun aForeignLoginIsARefusalNotASecondAccount() = runTest {
        val kept = AccountCredentials(login, password)
        val stored = Memory(StoredAccount.Pending(kept))

        val outcome = registration(
            stored,
            register = HttpStatusCode.Conflict,
            token = HttpStatusCode.Unauthorized
        ).ensure()

        assertEquals(AccountRegistration.Outcome.Failed(ApiFailure.Conflict), outcome)
        assertEquals(StoredAccount.Pending(kept), stored.account)
    }

    @Test
    fun confirmedAccountIsNotRegisteredAgain() = runTest {
        val stored = Memory(StoredAccount.Present(AccountCredentials(login, password)))

        assertEquals(AccountRegistration.Outcome.Ready, registration(stored).ensure())

        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun unreadableAccountIsNotReplacedSilently() = runTest {
        val stored = Memory(StoredAccount.Unreadable)

        assertEquals(AccountRegistration.Outcome.Unreadable, registration(stored).ensure())

        assertEquals(emptyList<String>(), requests)
        assertEquals(StoredAccount.Unreadable, stored.account)
    }

    /** Не записалось — на сервере ничего не заведено: терять нечего, и запроса не было. */
    @Test
    fun credentialsThatCouldNotBeStoredNeverReachTheServer() = runTest {
        val stored = Memory(StoredAccount.Absent, writable = false)
        val registration = registration(stored)

        assertEquals(AccountRegistration.Outcome.NotStored, registration.ensure())
        assertEquals(AccountRegistration.Outcome.NotStored, registration.ensure())

        assertEquals(StoredAccount.Absent, stored.account)
        assertEquals(emptyList<String>(), requests)
    }

    /** Отказ сервера данные не стирает: они записаны, и следующая попытка идёт теми же. */
    @Test
    fun refusedRegistrationKeepsTheInventedCredentials() = runTest {
        val stored = Memory(StoredAccount.Absent)

        val outcome = registration(stored, register = HttpStatusCode.Forbidden).ensure()

        assertEquals(AccountRegistration.Outcome.Failed(ApiFailure.RegistrationRefused), outcome)
        assertTrue("данные остались: $stored", stored.account is StoredAccount.Pending)
    }

    @Test
    fun simultaneousSetupRegistersOnce() = runTest {
        val registration = registration(Memory(StoredAccount.Absent))

        val outcomes = List(4) { async { registration.ensure() } }.awaitAll()

        assertEquals(List(4) { AccountRegistration.Outcome.Ready }, outcomes)
        assertEquals(1, requests.size)
    }

    /** Каждая новая учётка своя: пароль не берётся из общего места и не повторяется. */
    @Test
    fun inventedCredentialsAreNotTheSameTwice() {
        val first = AccountCredentials.random()
        val second = AccountCredentials.random()

        assertNotEquals(first.password, second.password)
        assertNotEquals(first.login, second.login)
        AccountPostNetworkDTO(first.login, first.password)
    }
}

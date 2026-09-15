package com.kert0n.medapp.network.account

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.server.medAppHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сеть выполняет доменный порт: исходы регистрации переводятся на язык домена, а коды ответа за
 * границу сети не уходят (PLAN B5, H1). Саму механику знакомства проверяет
 * `AccountRegistrationTest` — здесь только перевод.
 */
class ServerDeviceAccountTest {

    private class Memory(var account: StoredAccount, private val writable: Boolean = true) : CredentialSource {
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

    private fun account(stored: Memory, answer: (String) -> Pair<String, HttpStatusCode>) = ServerDeviceAccount(
        AccountRegistration(
            MedAppApi(
                medAppHttpClient(
                    MockEngine { request ->
                        val (body, status) = answer(request.url.encodedPath)
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                    "https://example.invalid"
                )
            ),
            stored,
            "токен",
            AccessTokens(stored)
        )
    )

    private val registered: (String) -> Pair<String, HttpStatusCode> = { path ->
        if (path == "/v1/auth/token") """{"accessToken":"t"}""" to HttpStatusCode.OK
        else "" to HttpStatusCode.Created
    }

    @Test
    fun aRegisteredDeviceIsReady() = runTest {
        val readiness = account(Memory(StoredAccount.Absent), registered).ensure()

        assertEquals(AccountReadiness.Ready, readiness)
    }

    /** Нечитаемое сохранённое — утрата ключа, а не отказ: поверх него не регистрируют (PLAN G2). */
    /** Решение переводится тем же словарём, что и знакомство: новая учётка — «готово». */
    @Test
    fun replacingTheUnreadableSpeaksTheSameWords() = runTest {
        val stored = Memory(StoredAccount.Unreadable)

        assertEquals(AccountReadiness.Ready, account(stored, registered).replaceUnreadable())
        assertTrue(stored.account is StoredAccount.Present)
    }

    @Test
    fun anUnreadableKeyIsLostNotRefused() = runTest {
        val readiness = account(Memory(StoredAccount.Unreadable), registered).ensure()

        assertEquals(AccountReadiness.KeyLost, readiness)
    }

    /** Не записались — на сервере ничего нет: исход настройки, и повторить её можно (PLAN G2). */
    @Test
    fun credentialsThatCouldNotBeStoredAreASetupOutcome() = runTest {
        val readiness = account(Memory(StoredAccount.Absent, writable = false), registered).ensure()

        assertEquals(AccountReadiness.NotReady(Unavailability.DEVICE_STORAGE), readiness)
    }

    /**
     * Соединение не установилось — запрос никуда не ушёл, и это отсутствие связи, а не молчание
     * сервера: человеку сказать надо разное.
     */
    @Test
    fun aBrokenConnectionIsNamedAsSuch() = runTest {
        val readiness = account(Memory(StoredAccount.Absent)) {
            throw java.net.ConnectException("связи нет")
        }.ensure()

        assertEquals(AccountReadiness.NotReady(Unavailability.NO_CONNECTION), readiness)
    }

    /** Токен сборки сервер не принял: повтор тем же не поможет, нужно решение человека (G1). */
    @Test
    fun aRefusedRegistrationTokenMeansTheServerRefusedUs() = runTest {
        val readiness = account(Memory(StoredAccount.Absent)) { "" to HttpStatusCode.Forbidden }.ensure()

        assertEquals(AccountReadiness.NotReady(Unavailability.SERVER_REFUSED_US), readiness)
    }

    /** Сервер ответил своей бедой: это не отказ нам и не отсутствие связи — повторить позже. */
    @Test
    fun aServerFailureIsItsOwnReason() = runTest {
        val readiness = account(Memory(StoredAccount.Absent)) { "" to HttpStatusCode.BadGateway }.ensure()

        assertEquals(AccountReadiness.NotReady(Unavailability.SERVER_SILENT), readiness)
    }

    /** Сохранённая учётка принимается без запроса: настроенное устройство открывается без связи. */
    @Test
    fun aStoredAccountIsReadyWithoutAsking() = runTest {
        val stored = Memory(StoredAccount.Present(AccountCredentials(Uuid.random(), "p".repeat(32))))

        val readiness = account(stored) { throw java.net.ConnectException("связи нет") }.ensure()

        assertEquals(AccountReadiness.Ready, readiness)
    }
}

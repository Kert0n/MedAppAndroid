@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kert0n.medapp.network.account

import com.kert0n.medapp.network.server.REGISTRATION_TOKEN_HEADER
import com.kert0n.medapp.network.server.medAppHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сервер забыл учётку устройства — боевую базу очистили. Устройство знает свои логин и пароль: их
 * придумало оно само, и регистрация теми же данными возвращает всё, что сервер ещё может дать.
 *
 * Ветка редкая, и главное здесь — чтобы она **не доставалась тому, у кого всё хорошо** (указание
 * владельца 2026-09-23): истёкший пропуск, упавшая выдача, недописанная учётка идут своими путями,
 * регистрации не зовут и учётных данных не трогают. Сервер здесь с памятью: какие логины он знает,
 * какие пропуски действуют и кто к нему приходил.
 */
class ForgottenAccountTest {

    private val account = AccountCredentials(
        login = Uuid.parse("00000000-0000-4000-8000-000000000071"),
        password = "k3y-придуман-устройством"
    )

    /** Хранилище учётки, которое считает каждую запись: «не переписываются» проверяется счётом. */
    private class Stored(var account: StoredAccount) : CredentialSource {
        val saves = AtomicInteger()
        val forgets = AtomicInteger()

        override suspend fun read(): StoredAccount = account

        override suspend fun save(credentials: AccountCredentials): CredentialsSaved {
            saves.incrementAndGet()
            account = StoredAccount.Pending(credentials)
            return CredentialsSaved.SAVED
        }

        override suspend fun confirm(): CredentialsSaved {
            (account as? StoredAccount.Pending)?.let { account = StoredAccount.Present(it.credentials) }
            return CredentialsSaved.SAVED
        }

        override suspend fun forget(): CredentialsSaved {
            forgets.incrementAndGet()
            account = StoredAccount.Absent
            return CredentialsSaved.SAVED
        }
    }

    /**
     * Сервер MedApp в памяти: знает учётки [known] с их паролями, выдаёт пропуски `t1`, `t2`, …,
     * принимает ресурс с действующим пропуском. [tokenFailure] — выдача отвечает этим статусом, не
     * глядя на учётку; [registerFailure] — регистрация отвечает им же.
     */
    private class Server {
        val known = HashMap<String, String>()
        val validTokens = HashSet<String>()
        val registered = ArrayList<Pair<String, String>>()
        val tokenCalls = AtomicInteger()
        val registerCalls = AtomicInteger()
        var tokenFailure: HttpStatusCode? = null
        var registerFailure: HttpStatusCode? = null
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(server: Server, tokens: AccessTokens): HttpClient = medAppHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/token" -> {
                    val number = server.tokenCalls.incrementAndGet()
                    server.tokenFailure?.let { return@MockEngine respond("", it) }
                    val (login, password) = basic(request.headers[HttpHeaders.Authorization])
                    if (server.known[login] != password) return@MockEngine respond("", HttpStatusCode.Unauthorized)
                    server.validTokens += "t$number"
                    respond("""{"accessToken":"t$number"}""", HttpStatusCode.OK, json)
                }
                "/v1/auth/register" -> {
                    server.registerCalls.incrementAndGet()
                    assertEquals("build-token", request.headers[REGISTRATION_TOKEN_HEADER])
                    server.registerFailure?.let { return@MockEngine respond("", it) }
                    val body = String(request.body.toByteArray())
                    val login = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                    val password = Regex("\"password\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                    if (login in server.known) return@MockEngine respond("", HttpStatusCode.Conflict)
                    server.known[login] = password
                    server.registered += login to password
                    respond("", HttpStatusCode.Created)
                }
                else -> {
                    val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
                    respond("", if (bearer in server.validTokens) HttpStatusCode.OK else HttpStatusCode.Unauthorized)
                }
            }
        },
        "https://medapp.test",
        tokens = tokens,
        retryDelay = { delayMillis(false) { 0L } }
    )

    private fun basic(header: String?): Pair<String, String> {
        val decoded = String(Base64.getDecoder().decode(requireNotNull(header).removePrefix("Basic ")))
        return decoded.substringBefore(':') to decoded.substringAfter(':')
    }

    private fun io.ktor.http.content.OutgoingContent.toByteArray(): ByteArray = when (this) {
        is io.ktor.http.content.OutgoingContent.ByteArrayContent -> bytes()
        is io.ktor.http.content.TextContent -> text.toByteArray()
        else -> error("тело регистрации неожиданного вида: $this")
    }

    private fun tokens(stored: Stored): AccessTokens = AccessTokens(stored)

    /**
     * **Сервер забыл учётку** — устройство регистрирует её заново **теми же** логином и паролем и
     * продолжает работу тем же запросом. Сохранённое не переписывается и не стирается.
     *
     * Красная проверка: выдача отвечала 401, `Rejected` запоминался на весь процесс, и каждый
     * запрос уходил без пропуска — общие полки молча переставали синхронизироваться навсегда.
     */
    @Test
    fun aForgottenAccountIsRegisteredAgainWithTheSameCredentials() = runTest {
        val server = Server()
        val stored = Stored(StoredAccount.Present(account))

        val response = client(server, tokens(stored)).get("/v1/users/me")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(account.login.toString() to account.password), server.registered)
        assertEquals(0, stored.saves.get())
        assertEquals(0, stored.forgets.get())
        assertEquals(StoredAccount.Present(account), stored.account)
    }

    /**
     * Страж: **истёкший пропуск — не забытая учётка.** 401 на обычный запрос перевыпускает пропуск и
     * повторяет запрос, как всегда; к регистрации это не ведёт.
     */
    @Test
    fun anExpiredPassIsReissuedWithoutRegistration() = runTest {
        val server = Server().apply { known[account.login.toString()] = account.password }
        val stored = Stored(StoredAccount.Present(account))
        val client = client(server, tokens(stored))
        assertEquals(HttpStatusCode.OK, client.get("/v1/users/me").status)
        server.validTokens.clear()

        assertEquals(HttpStatusCode.OK, client.get("/v1/users/me").status)

        assertEquals(2, server.tokenCalls.get())
        assertEquals(0, server.registerCalls.get())
        assertEquals(0, stored.saves.get() + stored.forgets.get())
    }

    /**
     * Страж: **упавшая выдача — не забытая учётка.** Сервер лежит, ограничивает частоту, отвечает не
     * по форме — это ничего не говорит о том, знает ли он нас, и регистрации не зовёт.
     */
    @Test
    fun aFailingTokenEndpointIsNotAForgottenAccount() = runTest {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests, HttpStatusCode.BadRequest, HttpStatusCode.Forbidden)) {
            val server = Server().apply { tokenFailure = status }
            val stored = Stored(StoredAccount.Present(account))

            kotlin.runCatching { client(server, tokens(stored)).get("/v1/users/me") }

            assertEquals("выдача ответила $status", 0, server.registerCalls.get())
            assertEquals("выдача ответила $status", 0, stored.saves.get() + stored.forgets.get())
            assertEquals(StoredAccount.Present(account), stored.account)
        }
    }

    /**
     * Страж: **только записанная и подтверждённая учётка** бывает забыта сервером. Недописанную
     * доводит настройка при запуске, отсутствующую заводит она же, нечитаемую решает человек, —
     * путь пропуска регистрацию не зовёт ни для одной.
     */
    @Test
    fun onlyAConfirmedAccountCanBeForgotten() = runTest {
        for (account in listOf(StoredAccount.Pending(account), StoredAccount.Absent, StoredAccount.Unreadable)) {
            val server = Server()
            val stored = Stored(account)

            kotlin.runCatching { client(server, tokens(stored)).get("/v1/users/me") }

            assertEquals("учётка $account", 0, server.registerCalls.get())
            assertEquals("учётка $account", 0, stored.saves.get() + stored.forgets.get())
            assertEquals(account, stored.account)
        }
    }
}

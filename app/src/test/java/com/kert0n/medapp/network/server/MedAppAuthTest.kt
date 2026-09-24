@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kert0n.medapp.network.server

import com.kert0n.medapp.domain.account.AccountCredentials
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.feature.account.AccountRegistration
import com.kert0n.medapp.feature.account.CredentialSource
import com.kert0n.medapp.feature.account.CredentialsSaved
import com.kert0n.medapp.feature.account.StoredAccount
import com.kert0n.medapp.network.account.AccessTokenThrottled
import com.kert0n.medapp.network.account.AccessTokenUnavailable
import com.kert0n.medapp.network.account.AccessTokens
import com.kert0n.medapp.network.account.ServerAccounts
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пропуск выдаётся по учётке и перевыпускается ровно один раз на 401; параллельные 401 делят
 * одну выдачу (PLAN B1, B5).
 */
class MedAppAuthTest {

    private val account = AccountCredentials(
        login = Uuid.parse("00000000-0000-4000-8000-000000000071"),
        password = "k3y"
    )

    private class Stored(var account: StoredAccount) : CredentialSource {
        override suspend fun read(): StoredAccount = account
        override suspend fun save(credentials: AccountCredentials): CredentialsSaved {
            account = StoredAccount.Pending(credentials)
            return CredentialsSaved.SAVED
        }

        override suspend fun confirm(): CredentialsSaved {
            (account as? StoredAccount.Pending)?.let { account = StoredAccount.Present(it.credentials) }
            return CredentialsSaved.SAVED
        }

        override suspend fun forget(): CredentialsSaved {
            account = StoredAccount.Absent
            return CredentialsSaved.SAVED
        }
    }

    private val tokenCalls = AtomicInteger()
    private val resourceCalls = AtomicInteger()
    private val seenAuthorization = mutableListOf<String?>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Сервер выдаёт пропуски `t1`, `t2`, … и принимает ресурс только с тем, что назовёт [accepts]. */
    private fun client(
        stored: StoredAccount = StoredAccount.Present(account),
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        retryAfter: String? = null,
        accepts: (String?) -> Boolean = { it == "Bearer t${tokenCalls.get()}" }
    ) = medAppHttpClient(
        MockEngine { request -> answer(request, tokenStatus, retryAfter, accepts) },
        "https://medapp.test",
        tokens = AccessTokens(Stored(stored)),
        retryDelay = { delayMillis(false) { 0L } }
    )

    private fun MockRequestHandleScope.answer(
        request: HttpRequestData,
        tokenStatus: HttpStatusCode,
        retryAfter: String?,
        accepts: (String?) -> Boolean
    ): HttpResponseData {
        val authorization = request.headers[HttpHeaders.Authorization]
        if (request.url.encodedPath == "/v1/auth/token") {
            val number = tokenCalls.incrementAndGet()
            val basic = "Basic " + Base64.getEncoder()
                .encodeToString("${account.login}:${account.password}".toByteArray())
            assertEquals(basic, authorization)
            return if (tokenStatus == HttpStatusCode.OK) {
                respond("""{"accessToken":"t$number"}""", HttpStatusCode.OK, json)
            } else {
                respond("", tokenStatus, retryAfter?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf())
            }
        }
        resourceCalls.incrementAndGet()
        synchronized(seenAuthorization) { seenAuthorization += authorization }
        return respond("", if (accepts(authorization)) HttpStatusCode.OK else HttpStatusCode.Unauthorized)
    }

    @Test
    fun firstRequestTakesATokenByBasicAndSendsItAsBearer() = runTest {
        val response = client().get("/v1/users/me")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, tokenCalls.get())
        assertEquals(listOf<String?>("Bearer t1"), seenAuthorization)
    }

    @Test
    fun tokenLivesInMemoryAndIsReused() = runTest {
        val client = client()
        client.get("/v1/users/me")
        client.get("/v1/med-kits")

        assertEquals(1, tokenCalls.get())
    }

    /** 401 значит, что команда не принята к исполнению, поэтому повтор безопасен и для расхода. */
    @Test
    fun expiredTokenIsReissuedOnceAndTheCommandRepeated() = runTest {
        var accepted = "Bearer t1"
        val client = client(accepts = { it == accepted })
        client.get("/v1/users/me")
        accepted = "Bearer t2"

        val response = client.post("/v1/drugs/00000000-0000-4000-8000-000000000011/intakes")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, tokenCalls.get())
        assertEquals(listOf<String?>("Bearer t1", "Bearer t1", "Bearer t2"), seenAuthorization)
    }

    @Test
    fun secondUnauthorizedIsReturnedNotLooped() = runTest {
        val response = client(accepts = { false }).get("/v1/users/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(2, tokenCalls.get())
        assertEquals(2, resourceCalls.get())
    }

    @Test
    fun parallelUnauthorizedShareOneIssue() = runTest {
        var accepted = "Bearer t1"
        val client = client(accepts = { it == accepted })
        client.get("/v1/users/me")
        accepted = "Bearer t2"

        val statuses = (1..8).map { async { client.get("/v1/med-kits").status } }.awaitAll()

        assertEquals(List(8) { HttpStatusCode.OK }, statuses)
        assertEquals(2, tokenCalls.get())
    }

    @Test
    fun withoutAnAccountNothingIsIssuedAndTheServerRefuses() = runTest {
        val response = client(stored = StoredAccount.Absent).get("/v1/users/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, tokenCalls.get())
        assertNull(seenAuthorization.single())
    }

    @Test
    fun unreadableAccountIsNotPresentedToTheServer() = runTest {
        client(stored = StoredAccount.Unreadable).get("/v1/users/me")

        assertEquals(0, tokenCalls.get())
    }

    @Test
    fun rejectedAccountEndsInUnauthorized() = runTest {
        val response = client(tokenStatus = HttpStatusCode.Unauthorized).get("/v1/users/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun tokenEndpointDownIsNotAnUnauthorizedAccount() = runTest {
        val failure = runCatching {
            client(tokenStatus = HttpStatusCode.ServiceUnavailable).get("/v1/users/me")
        }.exceptionOrNull()

        assertTrue(failure is AccessTokenUnavailable)
        assertEquals(0, resourceCalls.get())
    }

    /**
     * Учётку сервер не принял — по тому же ключу пропуск больше не просят: иначе каждый запрос
     * добавлял бы к ресурсу лишнюю выдачу и выжигал лимит по адресу (PLAN B5).
     */
    @Test
    fun rejectedAccountIsNotAskedForATokenAgain() = runTest {
        val client = client(tokenStatus = HttpStatusCode.Unauthorized)
        client.get("/v1/users/me")
        client.get("/v1/med-kits")

        assertEquals(1, tokenCalls.get())
        assertEquals(listOf<String?>(null, null), seenAuthorization)
    }

    /** Лимит выдачи — отдельный случай: срок ожидания называет сервер, и он доходит до операции. */
    @Test
    fun throttledIssueCarriesRetryAfter() = runTest {
        val client = client(tokenStatus = HttpStatusCode.TooManyRequests, retryAfter = "30")

        val failure = runCatching { client.get("/v1/users/me") }.exceptionOrNull()

        assertEquals(30.seconds, (failure as? AccessTokenThrottled)?.retryAfter)
        assertEquals(0, resourceCalls.get())
    }

    /**
     * Ждавшие одну выдачу берут её результат, даже когда она не удалась. Выдача держится
     * воротами, пока все восемь до неё не дошли: разделяется результат именно **ждавшими**, а
     * пришедший после неудачи пробует заново — недоступность преходяща и не запоминается.
     */
    @Test
    fun parallelRequestsShareOneFailedIssue() = runTest {
        val issuing = CompletableDeferred<Unit>()
        val client = medAppHttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/v1/auth/token") {
                    tokenCalls.incrementAndGet()
                    issuing.await()
                    respond("", HttpStatusCode.ServiceUnavailable)
                } else {
                    resourceCalls.incrementAndGet()
                    respond("", HttpStatusCode.OK)
                }
            },
            "https://medapp.test",
            tokens = AccessTokens(Stored(StoredAccount.Present(account))),
            retryDelay = { delayMillis(false) { 0L } }
        )

        val requests = (1..8).map {
            async { runCatching { client.get("/v1/users/me") }.exceptionOrNull() }
        }
        runCurrent()
        issuing.complete(Unit)
        val failures = requests.awaitAll()

        assertTrue(failures.all { it is AccessTokenUnavailable })
        assertEquals(1, tokenCalls.get())
        assertEquals(0, resourceCalls.get())
    }

    /**
     * Отмена — не отказ выдачи. Иначе брошенный экран оставлял бы в общем пропуске «выдать не
     * удалось», и следующий запрос получил бы это вместо своей попытки.
     */
    @Test
    fun cancellationDuringIssuePassesThrough() = runTest {
        val stalled = ByteChannel(autoFlush = true)
        val client = medAppHttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/v1/auth/token") {
                    tokenCalls.incrementAndGet()
                    respond(stalled, HttpStatusCode.OK, json)
                } else {
                    respond("", HttpStatusCode.OK)
                }
            },
            "https://medapp.test",
            tokens = AccessTokens(Stored(StoredAccount.Present(account)))
        )

        var caught: Throwable? = null
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            caught = runCatching { client.get("/v1/users/me") }.exceptionOrNull()
        }
        request.cancelAndJoin()

        assertTrue("отмена прошла насквозь, а не стала отказом: $caught", caught is CancellationException)
    }

    /**
     * Пропуск предъявляется своему адресу. Смотреть только на путь мало: чужой сервер отвечает по
     * тем же путям, и перенаправления к нему хватило бы, чтобы отдать ему пропуск.
     */
    @Test
    fun tokenIsNotSentToAnotherAddress() = runTest {
        val client = client()
        client.get("/v1/users/me")

        client.get("https://other.example/v1/users/me")
        client.get("http://medapp.test/v1/users/me")
        client.get("https://medapp.test:8443/v1/users/me")

        assertEquals(1, tokenCalls.get())
        assertEquals(listOf<String?>("Bearer t1", null, null, null), seenAuthorization)
    }

    /** Порт по умолчанию — тот же адрес: `https://medapp.test` и `:443` называют один сервер. */
    @Test
    fun theDefaultPortIsTheSameAddress() = runTest {
        client().get("https://medapp.test:443/v1/users/me")

        assertEquals(listOf<String?>("Bearer t1"), seenAuthorization)
    }

    /**
     * Перенаправления в контракте нет: 3xx возвращается как есть, и второго запроса — тем более
     * на чужой адрес — не случается. Это второе ограничение поверх привязки пропуска к адресу.
     */
    @Test
    fun redirectIsNotFollowed() = runTest {
        val visited = mutableListOf<String>()
        val client = medAppHttpClient(
            MockEngine { request ->
                visited += request.url.host
                if (request.url.encodedPath == "/v1/auth/token") {
                    respond("""{"accessToken":"t1"}""", HttpStatusCode.OK, json)
                } else {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://other.example/v1/users/me")
                    )
                }
            },
            "https://medapp.test",
            tokens = AccessTokens(Stored(StoredAccount.Present(account)))
        )

        val response = client.get("/v1/users/me")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(listOf("medapp.test", "medapp.test"), visited)
    }

    /**
     * Перенаправление на чужой хост с включённой политикой переходов: Ktor снимает заголовок сам,
     * но повторно проходит через этот хук, и раньше тот ставил пропуск обратно — по совпадению
     * пути. Привязка к адресу держит и здесь, независимо от политики переходов.
     */
    @Test
    fun tokenDoesNotSurviveACrossHostRedirect() = runTest {
        val visited = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                visited += request.url.host to request.headers[HttpHeaders.Authorization]
                when {
                    request.url.encodedPath == "/v1/auth/token" ->
                        respond("""{"accessToken":"t1"}""", HttpStatusCode.OK, json)
                    request.url.host == "medapp.test" -> respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://other.example/v1/users/me")
                    )
                    else -> respond("", HttpStatusCode.OK)
                }
            }
        ) {
            expectSuccess = false
            followRedirects = true
            install(ContentNegotiation) { json(medAppJson) }
            install(MedAppAuth) {
                tokens = AccessTokens(Stored(StoredAccount.Present(account)))
                origin = Url("https://medapp.test")
            }
            defaultRequest { url("https://medapp.test") }
        }

        client.get("/v1/users/me")

        assertEquals(listOf("medapp.test", "medapp.test", "other.example"), visited.map { it.first })
        assertNull("чужому адресу пропуск не предъявляют", visited.last().second)
    }

    @Test
    fun registrationGoesWithoutBearer() = runTest {
        client().post("/v1/auth/register")

        assertEquals(0, tokenCalls.get())
        assertNull(seenAuthorization.single())
    }

    /**
     * **Пропуск — учётки** (C1). Ключ утрачен, человек начал с новой учёткой: старый пропуск
     * сервер принимает ещё часы, но он пропуск чужой теперь учётки. Замена учётки обнуляет
     * пропуск, и следующий запрос идёт уже от нового имени — `t1`, потом `t2`, а не `t1` дважды.
     */
    @Test
    fun replacingTheAccountLeavesNoPassOfTheOldOne() = runTest {
        val stored = Stored(StoredAccount.Present(account))
        val tokens = AccessTokens(stored)
        val client = medAppHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/v1/auth/token" -> respond("""{"accessToken":"t${tokenCalls.incrementAndGet()}"}""", HttpStatusCode.OK, json)
                    "/v1/auth/register" -> respond("", HttpStatusCode.Created, json)
                    else -> {
                        seenAuthorization += request.headers[HttpHeaders.Authorization]
                        respond("", HttpStatusCode.OK)
                    }
                }
            },
            "https://medapp.test",
            tokens = tokens
        )
        client.get("/v1/users/me")
        stored.account = StoredAccount.Unreadable

        assertEquals(AccountReadiness.Ready, AccountRegistration(stored, ServerAccounts(MedAppApi(client), "build-token", tokens)).replaceUnreadable())
        client.get("/v1/users/me")

        assertEquals("после замены учётки запрос идёт со старым пропуском", listOf("Bearer t1", "Bearer t2"), seenAuthorization)
    }
}

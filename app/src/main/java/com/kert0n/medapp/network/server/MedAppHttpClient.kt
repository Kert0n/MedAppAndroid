package com.kert0n.medapp.network.server

import com.kert0n.medapp.network.account.AccessTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Клиент сервера MedApp (PLAN H2): строгий формат провода, таймауты и автоповтор **только
 * чтений**. Изменяющую команду HTTP-слой не повторяет: её исход мог примениться, и что делать
 * дальше, решает политика операции (PLAN E3), а не транспорт.
 *
 * Статус ответа исключением не становится: успех у каждой операции свой, и проверяет его тот,
 * кто операцию объявил. Лог пишется, только если передан [logger] (debug), и без секретов;
 * пропуск в запросы добавляет [MedAppAuth], если переданы [tokens].
 *
 * Перенаправлениям клиент не следует: в контракте их нет, и ответ 3xx — такой же ответ вне
 * контракта, как и любой другой незаявленный статус. Это второе ограничение поверх привязки
 * пропуска к адресу: политика, возвращённая по недосмотру, не должна оживлять утечку.
 *
 * Адрес — только `https`: клиент несёт то ключ учётки, то пропуск, то регистрационный токен, и
 * отдавать их открытым текстом нельзя ни в какой конфигурации (PLAN G3).
 */
fun medAppHttpClient(
    engine: HttpClientEngine,
    baseUrl: String,
    logger: Logger? = null,
    tokens: AccessTokens? = null,
    retryDelay: HttpRequestRetryConfig.() -> Unit = { exponentialDelay(randomizationMs = 500) }
): HttpClient = HttpClient(engine) {
    val origin = Url(baseUrl)
    // Открытым текстом здесь не ходит ничего: этот клиент несёт то ключ учётки в Basic, то
    // пропуск, то регистрационный токен сборки. Отладка против локального сервера по HTTP
    // (PLAN H2) потребует снять это ограничение осознанно, а не получить её умолчанием.
    require(origin.protocol == URLProtocol.HTTPS) { "адрес сервера MedApp — только https" }
    expectSuccess = false
    followRedirects = false
    if (tokens != null) {
        install(MedAppAuth) {
            this.tokens = tokens
            this.origin = origin
        }
    }
    if (logger != null) {
        install(Logging) {
            this.logger = SecretMaskingLogger(logger)
            level = LogLevel.ALL
            sanitizeHeader { it == HttpHeaders.Authorization || it == REGISTRATION_TOKEN_HEADER }
        }
    }
    install(ContentNegotiation) { json(medAppJson) }
    // Пределы — те же, что у клиента реестра маркировки: экран ждёт ответа не дольше, чем человек
    // готов смотреть на «ищу…». Без сети на эмуляторе соединение не отвергается, а молчит, и
    // отказ приходит по этому пределу (замечание владельца 2026-09-16).
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 15_000
        requestTimeoutMillis = 15_000
    }
    install(HttpRequestRetry) {
        retryIf(READ_RETRIES) { request, response ->
            request.method == HttpMethod.Get && response.status.value >= 500
        }
        retryOnExceptionIf(READ_RETRIES) { request, cause ->
            request.method == HttpMethod.Get && cause !is CancellationException
        }
        retryDelay()
    }
    defaultRequest { url(baseUrl) }
}

private const val READ_RETRIES = 3

/** Заголовок регистрации (PLAN B1): несёт токен сборки, поэтому в лог не попадает. */
const val REGISTRATION_TOKEN_HEADER = "X-Registration-Token"

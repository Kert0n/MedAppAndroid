package com.kert0n.medapp.network.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Клиент «Честного знака» (PLAN G3, H2). Чужой недокументированный API разбирается нестрого, а
 * авторизации MedApp у этого клиента нет вовсе: он отдельный экземпляр, чтобы токен не уехал на
 * посторонний хост случайной настройкой.
 */
fun crptHttpClient(engine: HttpClientEngine, baseUrl: String): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(crptJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
        requestTimeoutMillis = 15_000
    }
    defaultRequest { url(baseUrl) }
}

/** Нестрогий разбор чужого API — им же читает и проба, чтобы сверять живой ответ той же формой. */
internal val crptJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

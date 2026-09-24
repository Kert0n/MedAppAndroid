package com.kert0n.medapp.network.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Клиент реестра маркировки (PLAN G3, H2). Его адрес — целиком, с путём проверки — приходит из
 * сборки. Чужой ответ разбирается нестрого, а авторизации MedApp у этого клиента нет вовсе: он
 * отдельный экземпляр, чтобы токен не уехал на посторонний хост случайной настройкой.
 */
fun markingHttpClient(engine: HttpClientEngine, baseUrl: String): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(markingJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
        requestTimeoutMillis = 15_000
    }
    defaultRequest { url(baseUrl) }
}

/** Нестрогий разбор чужого ответа — им же читает и проба, чтобы сверять живой ответ той же формой. */
internal val markingJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

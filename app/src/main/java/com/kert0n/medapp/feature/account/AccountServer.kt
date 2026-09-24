package com.kert0n.medapp.feature.account

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountCredentials

/**
 * Что сценарию регистрации нужно от сервера: запомнить придуманную устройством учётку и забыть
 * пропуск прежней. Исполняет сеть, и коды ответа за её границу не уходят.
 */
interface AccountServer {

    /**
     * Сервер знает эту учётку — только что заведена или уже была нашей. Повтор теми же данными
     * безопасен: логин занят нами же, и это показывает пропуск по тем же данным.
     */
    suspend fun register(credentials: AccountCredentials): Registration

    /** Учётка сменилась: пропуск прежней больше не предъявляется (C1 «Пропуск — учётки»). */
    suspend fun forgetAccess()

    sealed interface Registration {

        data object Known : Registration

        data class Failed(val reason: Unavailability) : Registration
    }
}

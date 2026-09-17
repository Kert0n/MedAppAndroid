package com.kert0n.medapp.feature.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Есть ли у устройства выход к серверу (PLAN E4). Состояние устройства, а не вещи: без связи
 * спрашивать некого, и ждать ответа бессмысленно.
 *
 * Порт, а не механизм: знает об этом система, и слушает её платформа
 * (`platform/connectivity/SystemConnection`). Связью считается сеть, через которую наружу уже
 * выходили, а не просто поднятая: сеть гостиничного портала держала бы ждущего до таймаута.
 */
interface Connection {

    val online: StateFlow<Boolean>
}

/**
 * Связь вернулась: её не было, и она появилась. Связь, которая была при подписке, возвращением не
 * считается — тот, кто подписался, уже знает, что она есть.
 */
fun Connection.returns(): Flow<Unit> = flow {
    var was: Boolean? = null
    online.collect { now ->
        if (was == false && now) emit(Unit)
        was = now
    }
}

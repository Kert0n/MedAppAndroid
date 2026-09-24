package com.kert0n.medapp.network.server

import com.kert0n.medapp.queue.ResourceVersion
import kotlinx.serialization.Serializable

/**
 * Версия ресурса на проводе — просто число. Сетевая форма проверяет свой вход сама:
 * отрицательное не разбирается, и ответ с ним — ответ вне контракта, а не версия.
 */
@Serializable
@JvmInline
value class ResourceVersionNetworkDTO(val number: Long) {

    init {
        require(number >= 0) { "версия ресурса не бывает отрицательной: $number" }
    }

    fun toVersion(): ResourceVersion = ResourceVersion(number)
}

fun ResourceVersion.toNetworkDTO(): ResourceVersionNetworkDTO = ResourceVersionNetworkDTO(number)

package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Quantity

/**
 * Чем кончилась подготовка команды по свежему состоянию пачки — «подумали» перед отправкой
 * (PLAN E2, E3). Случая три, и очередь делает с ними разное: поручение уходит; отказ закрывает
 * операцию, не тревожа сервер, — состояние, к которому она обращена, для неё негодно; «уже
 * так» закрывает её применённой — на сервере и без нас то, чего команда хотела.
 */
sealed interface Preparation {

    /**
     * Уходит — с тем, по чему курьер соберёт посылку: подтверждённый остаток [confirmed], своя
     * бронь [mine] и сведения, какие у сервера сейчас, [known] (у создания и правки сведений).
     */
    data class Send(val confirmed: Quantity?, val mine: Quantity?, val known: PackageSharedFacts? = null) : Preparation

    data class Refuse(val reason: RefusalReason) : Preparation

    data object AlreadyApplied : Preparation
}

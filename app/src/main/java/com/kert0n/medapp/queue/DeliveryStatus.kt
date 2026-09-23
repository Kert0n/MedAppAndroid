package com.kert0n.medapp.queue

import kotlin.time.Duration

/**
 * Чем кончилась одна попытка курьера довезти поручение — словами поручения, а не провода.
 * Механический повтор той же посылки (обрыв, обновление пропуска) курьер уже сделал сам; что
 * делать со статусом дальше — повторить, собрать заново, закрыть, — решает очередь.
 */
sealed interface DeliveryStatus {

    /** Реестр ответил успехом; квитанция записывается до разбора. */
    data class Received(val receipt: Receipt) : DeliveryStatus

    /** Названная версия не текущая: реестр отверг поручение, не применяя. */
    data object Outdated : DeliveryStatus

    /** Номер занят: объект с ним уже есть, бронь уже заявлена или под ним применили другое. */
    data object Taken : DeliveryStatus

    /** Версия не названа вовсе — дефект поручения, а не состояние реестра. */
    data object VersionMissing : DeliveryStatus

    /** Реестр не принял содержимое поручения. */
    data object Invalid : DeliveryStatus

    /** Того, к чему обращено поручение, для нас нет — или не было никогда. */
    data object Absent : DeliveryStatus

    /** Учётку реестр не принял и после обновления пропуска. */
    data object NoPass : DeliveryStatus

    /** Реестр просит подождать; [retryAfter] — сколько, если сказал. */
    data class Throttled(val retryAfter: Duration?) : DeliveryStatus

    /** До реестра не дошло: ничего не применено. */
    data object Unreachable : DeliveryStatus

    /** Могло примениться, а ответа нет. */
    data object OutcomeUnknown : DeliveryStatus

    /** Ответ вне контракта. */
    data class Garbled(val reason: String) : DeliveryStatus
}

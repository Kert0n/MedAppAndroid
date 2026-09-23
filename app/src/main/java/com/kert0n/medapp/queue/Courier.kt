package com.kert0n.medapp.queue

import kotlin.uuid.Uuid

/**
 * Курьер поручений: везёт их реестру и возвращает статус, читает квитанции и снимки. Порт
 * объявлен очередью на её языке; исполняет его сеть. Механический повтор — та же посылка
 * побайтно после обрыва, обновление пропуска — забота курьера внутри одной попытки; логический —
 * пересобрать посылку, повторить позже, закрыть — очереди.
 */
interface Courier {

    /** Проход доставки: словарь в нём дочитывается не больше раза, сколько бы записей ни промахнулось. */
    fun pass(): Pass

    interface Pass {

        suspend fun send(request: PreparedRequest): DeliveryStatus

        /** Квитанция по форме, которую ждало поручение; снимок в ней собран в домен. */
        suspend fun read(receipt: Receipt, expects: Expected): Answer

        suspend fun packageSnapshot(packageId: Uuid): Fetched

        /**
         * Наша ли аптечка [medKitId]: реестр отдаёт только те, где мы участвуем, поэтому
         * «доступна» и «наша» одно и то же. `null` — проверить не удалось.
         */
        suspend fun medKitIsOurs(medKitId: Uuid): Boolean?

        /** Дочитать словарь, если в этом проходе ещё не дочитывали; `true` — дочитан сейчас. */
        suspend fun refreshVocabularyOnce(): Boolean

        /** Дочитать словарь в этом проходе не удалось. */
        val vocabularyRefreshFailed: Boolean
    }
}

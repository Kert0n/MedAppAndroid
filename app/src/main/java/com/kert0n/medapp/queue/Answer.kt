package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.pack.PackageSnapshot

/**
 * Квитанция, прочитанная по форме, которую ждало поручение ([Expected]), — и снимок в ней уже
 * собран в домен. Случаи — ровно те, что очередь закрывает по-разному.
 */
sealed interface Answer {

    data class Snapshot(val snapshot: PackageSnapshot) : Answer

    /** Ноль байтов там, где поручение его ждало: пачки в реестре больше нет. */
    data object Gone : Answer

    /** Бронь заявлена; снимок читается следом. */
    data object Claim : Answer

    data object Nothing : Answer

    /** Коробка в реестре — на полке, где нас нет: для нас это утрата доступа, окончательно (E6). */
    data object Elsewhere : Answer

    /** Собрать снимок пока нечем; [stop] — словарь не дочитался из-за связи. */
    data class Unresolved(val reason: String, val stop: Boolean) : Answer

    /** Квитанция не той формы: исход неизвестен. */
    data class Garbled(val reason: String) : Answer
}

/** Снимок пачки, прочитанный из реестра отдельно от поручения. */
sealed interface Fetched {

    data class Snapshot(val snapshot: PackageSnapshot) : Fetched

    data object Elsewhere : Fetched

    data class Unresolved(val reason: String, val stop: Boolean) : Fetched

    /** Прочитать не удалось — почему, говорит [status]. */
    data class Declined(val status: DeliveryStatus) : Fetched
}

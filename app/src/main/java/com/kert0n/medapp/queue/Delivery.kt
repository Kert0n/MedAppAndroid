package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.pack.PackageSnapshot
import java.time.Instant

/**
 * Чем кончилась отправка операции — ровно те случаи, которые хранилище записывает по-разному.
 * Неопределённости среди них нет: обрыв — это [Retry], а не состояние; «устарело» — известный
 * исход, после которого запрос готовится заново ([Stale]), и от [Retry] он отличается тем, что
 * повторяется **новый** запрос по полученному состоянию, а не тот же.
 */
sealed interface Delivery {

    /** Сервер сделал, что просили. [state] — истина по пачке: снимок, «пачки нет», ничего у аптечки. */
    data class Applied(val state: PackageState) : Delivery

    /**
     * Версия устарела, и команда хочет заново: снимок применяется, запрос сбрасывается, операция
     * снова ждёт и готовится по свежему состоянию под тем же номером (PLAN E3). [notBefore] —
     * когда сервер отвергает свежую версию раз за разом: дальше не сейчас, а по задержке.
     */
    data class Stale(val snapshot: PackageSnapshot, val notBefore: Instant? = null) : Delivery

    /** Сервер делать не будет. [state] — истина, прочитанная следом, где её было чем прочитать. */
    data class Refused(val reason: RefusalReason, val state: PackageState) : Delivery

    /**
     * Ответа не было: связь, сервер, ограничение частоты, ответ не по форме. Повтор тем же
     * запросом, не раньше [notBefore] — срок живёт в базе вместе с операцией, а не в памяти
     * прохода. Случая три, и поведение их различает: обрыв до сервера — не попытка
     * ([attempted] = `false`), задержка от него не растёт; сервер ответил, что не применял
     * (429), — попытка с известным исходом; ответ потерян или не по форме — попытка, чей исход
     * неизвестен ([outcomeUnknown]), и этот факт остаётся у запроса до его переподготовки.
     */
    data class Retry(
        val error: String,
        val notBefore: Instant? = null,
        val attempted: Boolean = true,
        val outcomeUnknown: Boolean = false
    ) : Delivery

    /** Пачки или аптечки на сервере для нас больше нет: отправлять некуда. */
    data object AccessLost : Delivery
}

package com.kert0n.medapp.storage.server

import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Незакрытая операция очереди **глазами экрана** (PLAN H3 №28). Форма своя, а не `StoredSyncOperation`:
 * очередь экрану не видна вовсе (`LayerBoundariesTest`), и её типы не могут ехать в представление —
 * ни импортом, ни полным именем. Здесь остаётся то, из чего человек принимает решение, и ничего
 * сверх: команда, запрос, версии и ответ сервера ему не говорят ничего.
 *
 * [subject] — имя вещи, о которой шла речь: коробки или полки. `null` — строки, которую нечем
 * прочитать: имя лежит внутри неё, и взять его неоткуда.
 */
data class OutstandingOperation(
    val id: Uuid,
    val trouble: Trouble,
    val subject: String?,
    val about: About,
    val reason: Reason?,
    val retryAt: Instant?,
    /** Коробка, которую можно пересчитать: расхождение по числу лечится пересчётом (REQ-045). */
    val recountable: Uuid?
) {

    /** Чем строка занимает человека. Случаи различает поведение экрана: ждать, решать, разобрать. */
    enum class Trouble {
        /** Ждёт отправки или ответа: делать нечего, и действий у строки нет. */
        WAITING,

        /** Сервер отверг: человек решает заново. */
        REFUSED,

        /** Строку нечем прочитать: чтением не лечится, остаётся разобрать (PLAN F4). */
        UNREADABLE
    }

    /** О чём шла речь — чтобы назвать строку словами человека, а не именем команды. */
    enum class About { PACKAGE_CREATED, PACKAGE_CHANGED, PACKAGE_MOVED, PACKAGE_REMOVED, INTAKE, CLAIM, MED_KIT, UNKNOWN }

    /**
     * Почему отвергли — **своим** перечислением, а не `RefusalReason` очереди: перевод через
     * границу слоя честнее, чем протащенный сквозь неё чужой тип (PLAN H1).
     */
    enum class Reason { INVALID, NOT_ENOUGH, UNIT_CHANGED, STALE, CONFLICT, SUPERSEDED, UNREADABLE }
}

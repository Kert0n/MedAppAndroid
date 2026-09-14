package com.kert0n.medapp.storage.notification

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Reminder
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Обязательства перед человеком (PLAN D8): что обещано сказать, на какой момент и в каком
 * состоянии. Порт хранения — доменных переходов здесь нет, есть действия над строками.
 *
 * Заводится обязательство **той же транзакцией, что и его повод**, а системные действия делает
 * владелец доставки, разбуженный [changes] уже после коммита (F5).
 */
interface ReminderStorageRepository {

    /** Сигнал после коммита: обязательства изменились — кто-то должен их исполнить. */
    fun changes(): Flow<Unit>

    /** Завести недостающие. Уже обещанное не трогается: отложенный человеком срок переживает сверку. */
    suspend fun raiseAll(reminders: Collection<Reminder>)

    suspend fun find(key: NotificationKey): Reminder?

    /** Наступившее и ещё не сказанное — этим способом доставки. */
    suspend fun due(now: Instant, delivery: NoticeDelivery): List<Reminder>

    /** Самое раннее невыполненное: к нему и будят процесс. `null` — будить незачем. */
    suspend fun nextDue(delivery: NoticeDelivery): Reminder?

    /** Отозванное, но ещё висящее в шторке. */
    suspend fun withdrawn(): List<Reminder>

    /** Всё обещанное этих видов — чтобы сверка знала, что уже обещано, и отозвала лишнее. */
    suspend fun ofKinds(kinds: Collection<NotificationKind>): List<Reminder>

    suspend fun defer(key: NotificationKey, until: Instant)

    suspend fun markShown(key: NotificationKey, at: Instant)

    suspend fun withdraw(keys: Collection<NotificationKey>)

    suspend fun forget(keys: Collection<NotificationKey>)

    /** Сказанное давно забывается: иначе таблица растёт всю жизнь установки. */
    suspend fun forgetShownBefore(before: Instant)
}

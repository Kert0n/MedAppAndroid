package com.kert0n.medapp.storage.notification

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.NotificationKey
import com.kert0n.medapp.domain.notification.NotificationKind
import com.kert0n.medapp.domain.notification.Reminder
import kotlinx.coroutines.flow.Flow

/**
 * Обязательства перед человеком (PLAN D8): что обещано сказать, на какой момент и в каком
 * состоянии. Порт **только пишет и читает** — решает всё сама сущность: наступило ли, к какому
 * моменту будить, что делать после неуспеха, можно ли забыть (C1 «Правила обязательства — на
 * обязательстве»). Глагольных `markShown`, `defer`, `withdraw` здесь нет намеренно: правило,
 * записанное запросом, разъезжается с тем, о чём оно говорит.
 *
 * Пишется обязательство **той же транзакцией, что и его повод**, а системные действия делает
 * владелец доставки, разбуженный [changes] уже после коммита (F5).
 */
interface ReminderStorageRepository {

    /** Сигнал после коммита: обязательства изменились — кто-то должен их исполнить. */
    fun changes(): Flow<Unit>

    /**
     * Сигнал после коммита: изменились **основания** обязательств — коробки, лечения, пункты,
     * очередь, — и обещанное надо сверить с ними заново. Какие это таблицы, знает хранение;
     * сами обязательства сюда не входят, иначе сверка будила бы себя своей же записью.
     */
    fun groundsChanged(): Flow<Unit>

    suspend fun find(key: NotificationKey): Reminder?

    suspend fun findAll(keys: Collection<NotificationKey>): List<Reminder>

    /**
     * Всё невыполненное этим способом доставки — и наступившее, и будущее. Что из него наступило и
     * к чему будить, решает [Reminder]; запрос таких вопросов не задаёт.
     */
    suspend fun awaiting(delivery: NoticeDelivery): List<Reminder>

    /** Отозванное: его надо погасить в системе и забыть. */
    suspend fun withdrawn(): List<Reminder>

    /** Строки старше названного момента — грубый отбор; забывать ли, решает [Reminder]. */
    suspend fun stale(before: java.time.Instant): List<Reminder>

    /** Всё обещанное этих видов — чтобы сверка знала, что уже обещано, и отозвала лишнее. */
    suspend fun ofKinds(kinds: Collection<NotificationKind>): List<Reminder>

    /** Записать состояние целиком: обязательство прочитали, изменили переходом и вернули. */
    suspend fun saveAll(reminders: Collection<Reminder>)

    suspend fun deleteAll(keys: Collection<NotificationKey>)
}

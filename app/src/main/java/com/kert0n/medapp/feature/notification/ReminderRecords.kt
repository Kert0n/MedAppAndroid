package com.kert0n.medapp.feature.notification

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
interface ReminderRecords : ReminderReadings {

    /**
     * Сигнал после коммита: обязательства изменились — кто-то должен их исполнить. Первое
     * значение — «наблюдатель встал», не изменение: тому, кто ждёт сигнала, есть чего дождаться.
     */
    fun changes(): Flow<Unit>

    /**
     * Сигнал после коммита: изменились **основания** обязательств — коробки, лечения, пункты,
     * очередь, — и обещанное надо сверить с ними заново. Какие это таблицы, знает хранение;
     * сами обязательства сюда не входят, иначе сверка будила бы себя своей же записью. Первое
     * значение — «наблюдатель встал», как у [changes].
     */
    fun groundsChanged(): Flow<Unit>

    suspend fun find(key: NotificationKey): Reminder?

    suspend fun findAll(keys: Collection<NotificationKey>): List<Reminder>

    /**
     * Всё невыполненное этим способом доставки — и наступившее, и будущее. Что из него наступило и
     * к чему будить, решает [Reminder]; запрос таких вопросов не задаёт.
     */
    suspend fun awaiting(delivery: NoticeDelivery): List<Reminder>

    /**
     * Без основания: отозванное — погасить и забыть — и то, о чём говорили, а оно теперь не
     * сказанное (отложено после показа) — погасить. Отбор грубый; решает [Reminder.cardIsUp].
     */
    suspend fun groundless(): List<Reminder>

    /** Строки старше названного момента — грубый отбор; забывать ли, решает [Reminder]. */
    suspend fun stale(before: java.time.Instant): List<Reminder>

    /** Всё обещанное этих видов — чтобы сверка знала, что уже обещано, и отозвала лишнее. */
    suspend fun ofKinds(kinds: Collection<NotificationKind>): List<Reminder>

    /** Записать состояние целиком: обязательство прочитали, изменили переходом и вернули. */
    suspend fun saveAll(reminders: Collection<Reminder>)

    suspend fun deleteAll(keys: Collection<NotificationKey>)
}

package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.PendingNotice
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface ReminderReadings {

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

    /**
     * То же невыполненное — потоком проекций для экрана (PLAN H3 №29): лист приёмов без пушей и
     * баннеры дня. Наступило ли, экран решает по `dueAt` и своим часам.
     */
    fun observeAwaiting(delivery: NoticeDelivery): Flow<List<PendingNotice>>
}

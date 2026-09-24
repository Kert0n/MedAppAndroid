package com.kert0n.medapp.feature.notification

import com.kert0n.medapp.domain.notification.NoticeDelivery
import com.kert0n.medapp.domain.notification.PendingNotice
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface ReminderReadings {

    /**
     * То же невыполненное — потоком проекций для экрана (PLAN H3 №29): лист приёмов без пушей и
     * баннеры дня. Наступило ли, экран решает по `dueAt` и своим часам.
     */
    fun observeAwaiting(delivery: NoticeDelivery): Flow<List<PendingNotice>>
}

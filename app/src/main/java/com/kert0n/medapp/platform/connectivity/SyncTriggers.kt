package com.kert0n.medapp.platform.connectivity

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kert0n.medapp.di.ApplicationScope
import com.kert0n.medapp.domain.attempt
import com.kert0n.medapp.feature.connectivity.Connection
import com.kert0n.medapp.feature.connectivity.returns
import com.kert0n.medapp.feature.delivery.Synchronization
import com.kert0n.medapp.feature.notification.DailyRound
import com.kert0n.medapp.feature.notification.ReminderOutbox
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Поводы синхронизации, которые приходят от платформы, пока процесс жив (PLAN E4): человек вошёл в
 * приложение — и связь появилась после того, как её не было. Оба зовут один координатор, и
 * совпавшие поводы сливаются в один заход. Вход в приложение ещё и приводит в порядок календарь
 * лечений — без сети.
 *
 * Вход — главный повод: истинное состояние нужно тогда, когда человек на него смотрит, и оставлять
 * приложение в неизвестном состоянии до фонового захода нельзя.
 */
@Singleton
class SyncTriggers @Inject constructor(
    private val connection: Connection,
    private val synchronization: Synchronization,
    private val daily: DailyRound,
    private val reminders: ReminderOutbox,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val started = AtomicBoolean(false)

    /** Один раз на процесс. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Календарь сети не ждёт и планировщика тоже: неответ, окно пунктов и сверка
                // обязательств делаются **в процессе**, здесь и сейчас (PLAN F4, D8). Через
                // WorkManager это откладывалось до его очереди, а быстрый повторный вход
                // политикой REPLACE отменял незаконченный проход.
                scope.launch { attempt { daily.run() } }
                // Вход — повод и для доставки: человек мог вернуться из настроек, где снял запрет, а
                // сверка без изменений ничего не пишет и владельца не будит. Сказано будет только
                // сегодняшнее (PLAN C1 «В шторку — только в свой день»).
                reminders.runNow()
                synchronization.request()
            }
        })
        // Связь, которая была при подписке, поводом не считается: вход в приложение уже позвал
        // заход. Повод — связь, появившаяся после потери.
        // Подписка ставится до возврата из `start`: связь, вернувшаяся между стартом и первым
        // значением, иначе сошла бы за «была при подписке», и повод потерялся бы.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { connection.returns().collect { synchronization.request() } }
    }
}

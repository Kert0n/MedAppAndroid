package com.kert0n.medapp.feature.course

import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseStorageRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Календарь идущих лечений в порядке (PLAN D6, F4): пункт, чей день **в зоне курса** кончился без
 * ответа, становится `MISSED` — отказ и неответ один случай, расхода нет, доза уезжает вперёд, — и
 * окно плановых пунктов достраивается. Выделения и брони это не трогает (D5).
 *
 * Сети здесь нет, и связь ей не нужна: зовут её при входе в приложение и регулярным фоновым
 * заходом (E4). Каждое лечение — своей транзакцией: одно, которое писать некуда, не держит
 * остальные. Проход один: второй вызов ждёт первого.
 */
@Singleton
class CourseUpkeep @Inject constructor(
    private val courses: CourseStorageRepository,
    private val calendar: CourseCalendar,
    private val transactions: Transactions,
    private val clock: Clock
) {

    private val single = Mutex()

    /** Сколько пунктов пропущено и сколько заведено этим проходом. */
    suspend fun keepUp(): Report = single.withLock {
        val now = clock.instant()
        var missed = 0
        var planned = 0
        for (id in courses.planIds()) {
            transactions.run {
                val course = courses.findPlan(id) ?: return@run
                val done = calendar.catchUp(course, now)
                missed += done.missed
                planned += done.planned
            }
        }
        Report(missed, planned)
    }

    data class Report(val missed: Int, val planned: Int)
}

package com.kert0n.medapp.feature.course

import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.Transactions
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Человек назвал лечение иначе или поправил заметку (PLAN D5). Это **не изменение лечения**: ни
 * доза, ни расписание, ни число приёмов не трогаются, будущие пункты не перестраиваются и
 * редакция плана не растёт — исправленная опечатка не меняет того, что человеку принимать.
 *
 * Имя лечения живёт у записи эпизода, и правится она: у плана имени нет вовсе, и второго места,
 * где то же имя могло бы разойтись, не существует.
 */
class CourseRenaming @Inject constructor(
    private val courses: CourseRecords,
    private val transactions: Transactions
) {

    /**
     * Длину названия и заметки держит сама запись. Переход применяется к записи, прочитанной
     * **этой же** транзакцией (PLAN F5): ноль изменённых строк после такого чтения незаконен, о
     * чём и говорит [readThisTransaction].
     *
     * Записью сущности целиком это не делается и порт остаётся узким: общая запись вернула бы
     * законченному лечению открытое состояние, а прочитанному когда-то экраном — его прежнее
     * назначение (F5). Хранение пишет ровно названное.
     */
    suspend fun rename(id: Uuid, title: String, note: String?): Outcome = transactions.run {
        val record = courses.findRecord(id) ?: return@run Outcome.GONE
        val renamed = record.rename(title, note)
        courses.rename(renamed.id, renamed.title, renamed.note).readThisTransaction("запись эпизода")
        Outcome.RENAMED
    }

    /** Чем кончилось: переименовали; эпизода нет — показывать и править уже нечего. */
    enum class Outcome { RENAMED, GONE }
}

package com.kert0n.medapp.feature.course

import com.kert0n.medapp.queue.Transactions
import com.kert0n.medapp.storage.course.CourseStorageRepository
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
    private val courses: CourseStorageRepository,
    private val transactions: Transactions
) {

    /** Длину названия и заметки держит сама запись; здесь — только дверь к ней. */
    suspend fun rename(id: Uuid, title: String, note: String?): Outcome = transactions.run {
        if (courses.rename(id, title, note)) Outcome.RENAMED else Outcome.GONE
    }

    /** Чем кончилось: переименовали; эпизода нет — показывать и править уже нечего. */
    enum class Outcome { RENAMED, GONE }
}

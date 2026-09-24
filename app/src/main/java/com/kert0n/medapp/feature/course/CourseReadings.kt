package com.kert0n.medapp.feature.course

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseDraftProjection
import com.kert0n.medapp.domain.course.CourseProjection
import com.kert0n.medapp.domain.course.CourseRecordProjection
import com.kert0n.medapp.domain.course.CoverageReduction
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface CourseReadings {

    fun observeDrafts(): Flow<List<CourseDraftProjection>>

    fun observePlan(id: Uuid): Flow<CourseProjection?>

    /**
     * Обеспечение идущего лечения — величина, которую считает курс по своему прогрессу и по
     * доступности своих пачек, как её видит человек: с очередью и без чужих броней (PLAN D4, D5).
     * План, приёмы и пачки читаются одним снимком. `null` — плана нет: лечение не начато или
     * закончено. Чужая коробка в расчёт не входит.
     */
    fun observeCoverage(id: Uuid): Flow<CourseCoverage?>

    /** То же по всем идущим лечениям сразу — списку курсов, где нехватка видна значком (H3 №13). */
    fun observeCoverages(): Flow<Map<Uuid, CourseCoverage>>

    /** Сокращения обеспечения эпизода по времени — карточке курса (PLAN D5). */
    fun observeReductions(courseId: Uuid): Flow<List<CoverageReduction>>

    /** Аналитика читает записи: идущее и законченное лечение для неё одной формы (PLAN H6). */
    fun observeRecords(): Flow<List<CourseRecordProjection>>

    fun observeRecord(id: Uuid): Flow<CourseRecordProjection?>
}

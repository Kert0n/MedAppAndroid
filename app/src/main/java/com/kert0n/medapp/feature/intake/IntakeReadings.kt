package com.kert0n.medapp.feature.intake

import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.intake.IntakeProjection
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/** Что экрану нужно от этого хранения: чтения потоком. Объявляет сценарий, исполняет хранение. */
interface IntakeReadings {

    /** Поток несёт проекции — величины для экрана; сущности отдают `find`/`ofCourse` в транзакции сценария. */
    fun observeOfCourse(courseId: Uuid): Flow<List<IntakeProjection>>

    /**
     * История коробки — факты приёма из неё, курсовые и разовые вместе, по моменту приёма
     * (PLAN D6). Читается по записи о коробке: кончившаяся коробка историю не теряет, а
     * одноимённая — другая запись, и её приёмы сюда не попадают.
     */
    fun observeOfPackage(packageId: Uuid): Flow<List<IntakeProjection>>

    /**
     * Приёмы, названные номерами. Обязательство знает только номер приёма
     * (`NotificationTarget.Intake`), а показать его человеку нужно лечением, дозой и пачкой —
     * оттого чтение и спрашивается номерами, а не курсом (PLAN D8, H3 «Уведомления на экране»).
     *
     * Пустой набор — пустой список, а не все приёмы: «ни о чём не спросили» и «спросили обо всём»
     * различаются.
     */
    fun observeOfIds(ids: Set<Uuid>): Flow<List<IntakeProjection>>
}

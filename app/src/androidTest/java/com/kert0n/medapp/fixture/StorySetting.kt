package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.feature.course.CourseDrafting
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.value.toStorageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * Декорации историй человека (`docs/истории.md`): словарь, домашняя аптечка и лечение, начатое
 * сценариями в названный момент. Завязка пишется сценариями напрямую — чужой путь переписывать
 * незачем; обязательства уведомлений декорации **не заводят**: их заводят календарь и проход дня.
 */
suspend fun MedAppDatabase.storySetting() {
    vocabulary().save(
        units = listOf(TABLETS).map { it.toStorageEntity() },
        forms = listOf(TABLET_FORM).map { it.toStorageEntity() }
    )
    medKits().insertIfMissing(medKit(id = HOME_KIT).toMedKitStorageEntity())
}

/** Лечение [title] из коробки [box], начатое в момент [at]: доза — таблетка, [days] дней по [times]. */
suspend fun MedAppDatabase.treatmentStarted(
    at: Instant,
    title: String,
    box: Uuid,
    start: LocalDate,
    times: List<LocalTime>,
    days: Int = 10,
    doseOf: String = "1"
): Uuid {
    val scenarios = Scenarios(this, at)
    val created = scenarios.courseDrafting.create(title)
    val saved = scenarios.courseDrafting.edit(
        created.id, created.revision,
        listOf(
            CourseDrafting.Edit.SetDose(dose(doseOf)),
            CourseDrafting.Edit.SetForm(TABLET_FORM),
            CourseDrafting.Edit.SetSchedule(schedule(start = start, times = times)),
            CourseDrafting.Edit.SetTotalDoses(Doses(times.size * days)),
            CourseDrafting.Edit.Attach(box, Doses(times.size * days))
        )
    ) as CourseDrafting.Outcome.Saved
    scenarios.courseActivation.activate(saved.draft.id, saved.draft.revision)
    return saved.draft.id
}

/** Пункт лечения в день [day] и час [hour] по Москве. */
suspend fun MedAppDatabase.intakeOn(course: Uuid, day: LocalDate, hour: Int, minute: Int = 0): CourseIntake =
    intakeRepository().ofCourse(course).filterIsInstance<CourseIntake>()
        .single { it.slot.localDate == day && it.slot.at.atZone(MOSCOW).let { t -> t.hour == hour && t.minute == minute } }

/** Момент по Москве. */
fun moscow(day: LocalDate, hour: Int, minute: Int = 0): Instant =
    LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(MOSCOW).toInstant()

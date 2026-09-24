package com.kert0n.medapp.presentation.settings

import com.kert0n.medapp.domain.notification.NotificationSettings
import com.kert0n.medapp.feature.settings.AppSettings
import com.kert0n.medapp.feature.settings.SyncInterval
import com.kert0n.medapp.feature.settings.SyncIntervalStep
import com.kert0n.medapp.presentation.ParsedInput
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор формы настроек (PLAN H3 №27): строки экрана становятся величиной или названной
 * причиной отказа — без исключений и без повторения правил, которые живут у типов.
 */
class SettingsFormMapperTest {

    private val filled = AppSettings(
        notifications = NotificationSettings(
            intakeRemindersEnabled = false,
            snoozeMinutes = 20,
            expirySourceRemindersEnabled = true,
            coverageThresholdDays = 5,
            digestEnabled = false,
            digestAt = LocalTime.of(20, 0),
            remoteChangeEnabled = false
        ),
        syncInterval = SyncInterval(240)
    )

    /** Форма показывает записанное и отдаёт его обратно тем же: иначе «Сохранить» без правок меняло бы настройки. */
    @Test
    fun whatIsWrittenComesBackUnchanged() {
        assertEquals(ParsedInput.Parsed(filled), filled.toFormPresentationDTO().parsed())
    }

    /**
     * Интервал вне пределов не набрать вовсе: выбирают из ступеней, и каждая — законный
     * `SyncInterval`. Ступень, которая не строится, уронила бы экран при открытии меню.
     */
    @Test
    fun everyStepIsALawfulInterval() {
        for (step in SyncIntervalStep.entries) {
            assertEquals(step.minutes, step.interval.minutes)
        }
        assertEquals(SyncInterval.MIN_MINUTES, SyncIntervalStep.entries.first().minutes)
        assertEquals(SyncInterval.MAX_MINUTES, SyncIntervalStep.entries.last().minutes)
    }

    /** Записанное число, не совпадающее ни с одной ступенью, показывается ближайшей, а не роняет форму. */
    @Test
    fun anOddStoredIntervalShowsAsTheClosestStep() {
        val odd = AppSettings(syncInterval = SyncInterval(50))

        assertEquals(60L, odd.toFormPresentationDTO().syncIntervalMinutes)
    }

    /** Буквы в минутах — отказ у своего поля, а не исключение разбора и не молчаливый ноль. */
    @Test
    fun snoozeThatIsNotANumberIsRejectedByName() {
        val form = filled.toFormPresentationDTO().copy(snoozeMinutes = "abc")

        assertEquals(ParsedInput.Rejected(SettingsFormError.Input.SNOOZE_NOT_A_NUMBER), form.parsed())
    }

    /** «Только вперёд» — правило `NotificationSettings`; разбор спрашивает тип, а не повторяет `require`. */
    @Test
    fun zeroSnoozeIsRejectedWithoutAnException() {
        val form = filled.toFormPresentationDTO().copy(snoozeMinutes = "0")

        assertEquals(ParsedInput.Rejected(SettingsFormError.Input.SNOOZE_NOT_FORWARD), form.parsed())
    }

    /** Отрицательных дней не бывает: без отказа тип уронил бы разбор своим `require`. */
    @Test
    fun negativeThresholdIsRejectedByName() {
        val form = filled.toFormPresentationDTO().copy(coverageThresholdDays = "-1")

        assertEquals(ParsedInput.Rejected(SettingsFormError.Input.THRESHOLD_NEGATIVE), form.parsed())
    }

    /** Пустое поле дней — «не число», а не ноль: иначе стёртое поле молча выключило бы предупреждение. */
    @Test
    fun emptyThresholdIsNotANumber() {
        val form = filled.toFormPresentationDTO().copy(coverageThresholdDays = " ")

        assertEquals(ParsedInput.Rejected(SettingsFormError.Input.THRESHOLD_NOT_A_NUMBER), form.parsed())
    }

    /** Пробелы вокруг числа — не часть его: человек их не видит. */
    @Test
    fun surroundingSpacesAreNotPartOfTheNumber() {
        val form = filled.toFormPresentationDTO().copy(snoozeMinutes = " 45 ", coverageThresholdDays = "0 ")

        val parsed = form.parsed()

        assertTrue(parsed is ParsedInput.Parsed)
        assertEquals(45, (parsed as ParsedInput.Parsed).value.notifications.snoozeMinutes)
        assertEquals(0L, parsed.value.notifications.coverageThresholdDays)
    }
}

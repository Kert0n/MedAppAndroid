package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.domain.course.CourseSchedule
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.MOSCOW
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.fixture.dose
import com.kert0n.medapp.fixture.schedule
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.FormPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.presentation.value.toPresentationDTO
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор формы лечения (PLAN H3 №15): черновику хватает названия, каждое заполненное поле
 * разбирается своим разбором, расписание — целиком или никак.
 */
class CourseFormMapperTest {

    private val monday = LocalDate.of(2027, 3, 1)

    private fun full() = CourseFormPresentationDTO(
        title = "Нурофен",
        doseAmount = "2",
        unit = TABLETS.toPresentationDTO(),
        form = TABLET_FORM.toPresentationDTO(),
        start = monday,
        days = DayOfWeek.entries.toSet(),
        times = listOf(LocalTime.of(21, 0), LocalTime.of(9, 0)),
        totalDoses = "7",
        zone = MOSCOW
    )

    /**
     * Черновику довольно названия, а пустая заметка — **отсутствие**, а не пустая строка: иначе
     * «записал у врача, куплю завтра» не сохранить, а в базе заводятся пустые заметки (H3 §15, D5).
     * Пробелы вокруг названия не его часть — иначе «Нурофен» и «Нурофен » разные лечения.
     */
    @Test
    fun aTitleAloneIsEnoughAndAnEmptyNoteIsAbsence() {
        val parsed = CourseFormPresentationDTO(title = "  Нурофен  ", note = "  ").parsed(VOCABULARY)

        assertEquals(ParsedInput.Parsed(CourseDescription("Нурофен", null)), parsed)
    }

    /**
     * Единица без числа дозой не становится и записи не мешает: черновик «название и форма
     * выпуска» — законная запись (H3 §15), а единицу к следующему открытию принесёт форма выпуска.
     */
    @Test
    fun aUnitWithoutAnAmountIsNoDoseAndNoRefusal() {
        val parsed = CourseFormPresentationDTO(title = "Нурофен", unit = TABLETS.toPresentationDTO())
            .parsed(VOCABULARY)

        assertEquals(ParsedInput.Parsed(CourseDescription("Нурофен", null)), parsed)
    }

    /** Черновик «название и форма выпуска» записывается: дозы у него нет вовсе, а не половина (H3 §15). */
    @Test
    fun aDraftWithATitleAndAFormIsWritten() {
        val parsed = CourseFormPresentationDTO(title = "Нурофен", form = TABLET_FORM.toPresentationDTO())
            .parsed(VOCABULARY)

        assertEquals(ParsedInput.Parsed(CourseDescription("Нурофен", null, dose = null, form = TABLET_FORM)), parsed)
    }

    /**
     * Название обязательно, и отказ называет своё поле: без него лечение не отличить в списке, а
     * безымянный отказ человеку некуда приложить (H3 §15).
     */
    @Test
    fun anEmptyTitleIsRefusedByName() {
        assertEquals(
            ParsedInput.Rejected(CourseFormError.Input.TITLE_EMPTY),
            CourseFormPresentationDTO(title = "   ", note = "купить").parsed(VOCABULARY)
        )
    }

    /** Предел берётся у записи: длиннее её названия черновик не заведёт. */
    @Test
    fun theLimitsAreThoseOfTheRecord() {
        val longTitle = "н".repeat(CourseRecord.TITLE_MAX_LENGTH + 1)
        val longNote = "з".repeat(CourseRecord.NOTE_MAX_LENGTH + 1)

        assertEquals(ParsedInput.Rejected(CourseFormError.Input.TITLE_TOO_LONG), CourseFormPresentationDTO(title = longTitle).parsed(VOCABULARY))
        assertEquals(
            ParsedInput.Rejected(CourseFormError.Input.NOTE_TOO_LONG),
            CourseFormPresentationDTO(title = "Нурофен", note = longNote).parsed(VOCABULARY)
        )
    }

    /** Полное назначение разбирается в то, что примет сценарий; времена — по порядку. */
    @Test
    fun aFullPrescriptionParsesIntoDomainValues() {
        val parsed = full().parsed(VOCABULARY) as ParsedInput.Parsed

        assertEquals(dose("2"), parsed.value.dose)
        assertEquals(TABLET_FORM, parsed.value.form)
        assertEquals(schedule(start = monday, times = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))), parsed.value.schedule)
        assertEquals(Doses(7), parsed.value.totalDoses)
    }

    /**
     * Доза разбирается тем же разбором, что количество коробки: число без единицы — отказ у
     * единицы, ноль — не доза, слово вместо числа — отказ величины.
     */
    @Test
    fun theDoseIsParsedLikeAQuantityAndNamesItsField() {
        assertEquals(
            ParsedInput.Rejected(CourseFormError.Input.UNIT_MISSING),
            full().copy(unit = null).parsed(VOCABULARY)
        )
        assertEquals(ParsedInput.Rejected(CourseFormError.Input.DOSE_IS_ZERO), full().copy(doseAmount = "0").parsed(VOCABULARY))
        assertEquals(
            ParsedInput.Rejected(CourseFormError.Dose(QuantityPresentationError.NOT_A_DECIMAL)),
            full().copy(doseAmount = "две").parsed(VOCABULARY)
        )
        assertEquals(CourseFormError.Field.DOSE, CourseFormError.Dose(QuantityPresentationError.NOT_A_DECIMAL).field)
    }

    /** Форма из устаревшего снимка — отказ, а не падение. */
    @Test
    fun aFormUnknownToTheVocabularyIsRefused() {
        val stale = full().copy(form = FormPresentationDTO(Uuid.random(), "порошок"))

        assertEquals(ParsedInput.Rejected(CourseFormError.Input.FORM_UNKNOWN), stale.parsed(VOCABULARY))
    }

    /**
     * Расписание — целиком или никак: ничего не названо — расписания нет, и это не отказ;
     * названо хоть что-то — не хватать не должно ничего, и сказано, чего.
     *
     * Красная проверка: собирать расписание из того, что есть, — календарь без дней недели упадёт
     * в домене, а не объяснится человеку.
     */
    @Test
    fun aScheduleIsWholeOrAbsent() {
        val none = full().copy(start = null, days = emptySet(), times = emptyList()).parsed(VOCABULARY) as ParsedInput.Parsed
        assertEquals(null, none.value.schedule)

        assertEquals(ParsedInput.Rejected(CourseFormError.Input.START_MISSING), full().copy(start = null).parsed(VOCABULARY))
        assertEquals(ParsedInput.Rejected(CourseFormError.Input.DAYS_EMPTY), full().copy(days = emptySet()).parsed(VOCABULARY))
        assertEquals(ParsedInput.Rejected(CourseFormError.Input.TIMES_EMPTY), full().copy(times = emptyList()).parsed(VOCABULARY))
    }

    /** Повтор времени и секунды — не отказ, а приведение: расписание требует минут без повторов. */
    @Test
    fun timesAreBroughtToWhatTheScheduleRequires() {
        val messy = full().copy(times = listOf(LocalTime.of(9, 0, 30), LocalTime.of(9, 0), LocalTime.of(8, 15)))

        val parsed = messy.parsed(VOCABULARY) as ParsedInput.Parsed

        assertEquals(listOf(LocalTime.of(8, 15), LocalTime.of(9, 0)), parsed.value.schedule?.times)
    }

    /**
     * Приёмов бывает целое положительное число: без правила ноль, дробь и слово уезжают в домен,
     * который ждёт готовую величину, — и расписание строится по мусору (H1, D5).
     */
    @Test
    fun theNumberOfDosesIsAWholePositiveNumber() {
        assertEquals(ParsedInput.Rejected(CourseFormError.Input.TOTAL_DOSES_INVALID), full().copy(totalDoses = "0").parsed(VOCABULARY))
        assertEquals(ParsedInput.Rejected(CourseFormError.Input.TOTAL_DOSES_INVALID), full().copy(totalDoses = "семь").parsed(VOCABULARY))
        assertEquals(ParsedInput.Rejected(CourseFormError.Input.TOTAL_DOSES_INVALID), full().copy(totalDoses = "1.5").parsed(VOCABULARY))
        val blank = full().copy(totalDoses = " ").parsed(VOCABULARY) as ParsedInput.Parsed
        assertEquals(null, blank.value.totalDoses)
    }

    /** «Дата конца» читается, а не вводится: семь приёмов по два в день с понедельника кончаются в четверг. */
    @Test
    fun theExpectedEndIsReadFromTheScheduleAndTheCount() {
        assertEquals(LocalDate.of(2027, 3, 4), full().expectedEnd())
        assertEquals(null, full().copy(totalDoses = "").expectedEnd())
        assertEquals(null, full().copy(days = emptySet()).expectedEnd())
    }

    /**
     * Ожидаемый конец считается на каждую набранную цифру — и на любое число, которое человек
     * успел набрать или вставить: ответ или его отсутствие, но не падение приложения (ТЗ 4.3).
     *
     * Красная проверка: `CourseSchedule.next` заранее выделял список на всё число доз, и
     * 2147483647 ронял форму `OutOfMemoryError` прямо при наборе.
     */
    @Test
    fun anAbsurdNumberOfDosesDoesNotCrashTheExpectedEnd() {
        full().copy(totalDoses = Int.MAX_VALUE.toString()).expectedEnd()
    }

    /**
     * Записанный черновик возвращается в форму тем же, чем был набран: иначе человек правит не то,
     * что видел, и «Сохранить» переписывает поля, которых он не трогал (U1 «ввод не затирается»).
     */
    @Test
    fun aStoredDraftComesBackAsItWasTyped() {
        val stored = course(
            title = "Нурофен",
            note = "по 2 после еды",
            dose = dose("2"),
            form = TABLET_FORM,
            schedule = CourseSchedule(monday, setOf(DayOfWeek.MONDAY), listOf(LocalTime.of(9, 0)), MOSCOW),
            totalDoses = 7
        ).projection()

        val form = stored.toFormPresentationDTO()

        assertEquals("Нурофен", form.title)
        assertEquals("по 2 после еды", form.note)
        assertEquals("2", form.doseAmount)
        assertEquals(TABLETS.toPresentationDTO(), form.unit)
        assertEquals(TABLET_FORM.toPresentationDTO(), form.form)
        assertEquals(monday, form.start)
        assertEquals(setOf(DayOfWeek.MONDAY), form.days)
        assertEquals(listOf(LocalTime.of(9, 0)), form.times)
        assertEquals("7", form.totalDoses)
        assertEquals(MOSCOW, form.zone)
        // Единица черновика может быть и другой: форма держит ту, что записана.
        assertEquals(MILLILITRES.toPresentationDTO(), course(dose = dose(com.kert0n.medapp.domain.value.Quantity(java.math.BigDecimal("5"), MILLILITRES))).projection().toFormPresentationDTO().unit)
    }
}

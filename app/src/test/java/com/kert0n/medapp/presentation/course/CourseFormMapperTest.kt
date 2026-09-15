package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseRecord
import com.kert0n.medapp.fixture.course
import com.kert0n.medapp.presentation.ParsedInput
import org.junit.Assert.assertEquals
import org.junit.Test

/** Разбор формы лечения (PLAN H3 №15): черновику хватает названия, а пределы — у записи эпизода. */
class CourseFormMapperTest {

    @Test
    fun aTitleAloneIsEnoughAndAnEmptyNoteIsAbsence() {
        val parsed = CourseFormPresentationDTO(title = "  Нурофен  ", note = "  ").parsed()

        assertEquals(ParsedInput.Parsed(CourseDescription("Нурофен", null)), parsed)
    }

    @Test
    fun anEmptyTitleIsRefusedByName() {
        assertEquals(
            ParsedInput.Rejected(CourseFormError.Input.TITLE_EMPTY),
            CourseFormPresentationDTO(title = "   ", note = "купить").parsed()
        )
    }

    /** Предел берётся у записи: длиннее её названия черновик не заведёт. */
    @Test
    fun theLimitsAreThoseOfTheRecord() {
        val longTitle = "н".repeat(CourseRecord.TITLE_MAX_LENGTH + 1)
        val longNote = "з".repeat(CourseRecord.NOTE_MAX_LENGTH + 1)

        assertEquals(ParsedInput.Rejected(CourseFormError.Input.TITLE_TOO_LONG), CourseFormPresentationDTO(title = longTitle).parsed())
        assertEquals(
            ParsedInput.Rejected(CourseFormError.Input.NOTE_TOO_LONG),
            CourseFormPresentationDTO(title = "Нурофен", note = longNote).parsed()
        )
    }

    @Test
    fun aStoredDraftComesBackAsItWasTyped() {
        val form = course(title = "Нурофен", note = "по 2 после еды").projection().toFormPresentationDTO()

        assertEquals(CourseFormPresentationDTO(title = "Нурофен", note = "по 2 после еды"), form)
    }
}

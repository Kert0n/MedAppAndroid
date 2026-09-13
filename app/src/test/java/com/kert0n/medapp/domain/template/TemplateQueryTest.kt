package com.kert0n.medapp.domain.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Запрос справочника — обрезанный текст от одного до двухсот символов (PLAN B4). */
class TemplateQueryTest {

    @Test
    fun aTrimmedTextUpToTheLimitIsAQuery() {
        assertEquals("а".repeat(TemplateQuery.MAX_LENGTH), TemplateQuery("а".repeat(TemplateQuery.MAX_LENGTH)).text)
    }

    @Test
    fun blankUntrimmedAndTooLongAreNotQueries() {
        for (text in listOf("", "  ", " парацетамол", "а".repeat(TemplateQuery.MAX_LENGTH + 1))) {
            assertTrue(text, runCatching { TemplateQuery(text) }.isFailure)
        }
    }
}

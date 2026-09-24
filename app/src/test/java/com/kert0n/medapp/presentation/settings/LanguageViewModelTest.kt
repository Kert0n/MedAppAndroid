package com.kert0n.medapp.presentation.settings

import com.kert0n.medapp.feature.settings.AppLanguage
import com.kert0n.medapp.fixture.FakeAppLanguages
import org.junit.Assert.assertEquals
import org.junit.Test

/** Язык (PLAN H3 №27): отмечено то, что хранит система; выбор доходит до неё и только новый. */
class LanguageViewModelTest {

    /** Отмечено то, что хранит система: иначе экран показывал бы «Как в системе», когда выбран английский. */
    @Test
    fun theCurrentLanguageComesFromTheSystem() {
        val model = LanguageViewModel(FakeAppLanguages(AppLanguage.ENGLISH))

        assertEquals(LanguageChoice.ENGLISH, model.state.value)
    }

    /** Выбор доходит до системы и виден: иначе отметка и язык приложения разошлись бы. */
    @Test
    fun choosingReachesTheSystemAndIsShown() {
        val languages = FakeAppLanguages()
        val model = LanguageViewModel(languages)

        model.choose(LanguageChoice.RUSSIAN)

        assertEquals(listOf(AppLanguage.RUSSIAN), languages.choices)
        assertEquals(LanguageChoice.RUSSIAN, model.state.value)
    }

    /** Нажатие на уже выбранное ничего не меняет — и окно не пересоздаётся зря. */
    @Test
    fun choosingTheSameLanguageAgainDoesNothing() {
        val languages = FakeAppLanguages(AppLanguage.RUSSIAN)
        val model = LanguageViewModel(languages)

        model.choose(LanguageChoice.RUSSIAN)

        assertEquals(emptyList<AppLanguage>(), languages.choices)
    }
}

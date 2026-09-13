package com.kert0n.medapp.feature.bootstrap

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.domain.account.DeviceAccount
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyLibrary
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Начало работы приложения (PLAN C3, G2, J3). Сценарий видит доменные порты, поэтому проверяется
 * его решение, а не чужая механика: как устройство знакомится с сервером по проводу, здесь не
 * знают — это забота сети и её собственных тестов.
 */
class AppStartTest {

    private val tablets = QuantityUnit(Uuid.parse("00000000-0000-4000-8000-000000000001"), "таблетка")

    private class Account(private val readiness: AccountReadiness) : DeviceAccount {
        var asked = 0
        override suspend fun ensure(): AccountReadiness {
            asked++
            return readiness
        }
    }

    private class Library(
        private var vocabulary: Vocabulary,
        private val problem: Unavailability? = null,
        private val refreshedTo: Vocabulary? = null
    ) : VocabularyLibrary {
        var refreshed = 0
        override suspend fun known(): Vocabulary = vocabulary
        override suspend fun refresh(): Unavailability? {
            refreshed++
            if (problem == null) {
                vocabulary = refreshedTo ?: Vocabulary(listOf(QuantityUnit(Uuid.random(), "штука")), emptyList())
            }
            return problem
        }
    }

    @Test
    fun aFreshDeviceRegistersAndReadsTheVocabulary() = runTest {
        val library = Library(Vocabulary.empty)

        val state = AppStart(Account(AccountReadiness.Ready), library).begin()

        assertEquals(AppStart.Outcome.Ready, state)
        assertEquals(1, library.refreshed)
    }

    /**
     * Сохранённое есть, но не открывается: приложение спрашивает, а не заводит молча вторую
     * учётную запись поверх локальных данных (PLAN G2).
     *
     * Красная проверка: свести утрату ключа к обычному отказу — экран предложит «повторить»,
     * и повтор пойдёт регистрировать заново.
     */
    @Test
    fun aLostKeyAsksTheHumanInsteadOfRegisteringAgain() = runTest {
        val state = AppStart(Account(AccountReadiness.KeyLost), Library(Vocabulary.empty)).begin()

        assertEquals(AppStart.Outcome.KeyLost, state)
    }

    /** Причина отказа доезжает до экрана как есть: он по ней и выбирает, что сказать. */
    @Test
    fun theReasonOfARefusedSetupReachesTheScreen() = runTest {
        for (reason in Unavailability.entries) {
            val account = Account(AccountReadiness.NotReady(reason))

            val state = AppStart(account, Library(Vocabulary.empty)).begin()

            assertEquals(AppStart.Outcome.Setup(reason), state)
        }
    }

    /** Словарь нужен, чтобы показать хоть одно количество: без него настройка не закончена. */
    @Test
    fun withoutAnyVocabularyTheSetupIsNotDone() = runTest {
        val library = Library(Vocabulary.empty, problem = Unavailability.NO_CONNECTION)

        val state = AppStart(Account(AccountReadiness.Ready), library).begin()

        assertEquals(AppStart.Outcome.Setup(Unavailability.NO_CONNECTION), state)
    }

    /**
     * Словарь из одних форм количества не измеряет: «не пуст» и «есть чем считать» — разные
     * вопросы, и настройка спрашивает второй.
     *
     * Красная проверка: сравнить снимок с пустым словарём — случай краснеет, приложение
     * открывается без единиц.
     */
    @Test
    fun formsWithoutUnitsAreNotEnoughToStart() = runTest {
        val onlyForms = Vocabulary(emptyList(), listOf(DosageForm(Uuid.random(), "таблетки")))
        val library = Library(onlyForms, problem = Unavailability.NO_CONNECTION)

        val state = AppStart(Account(AccountReadiness.Ready), library).begin()

        assertEquals(AppStart.Outcome.Setup(Unavailability.NO_CONNECTION), state)
    }

    /** Сервер ответил, а единиц всё равно нет: считать по-прежнему нечем, и настройка не закончена. */
    @Test
    fun aRefreshThatBroughtNoUnitsDoesNotFinishTheSetup() = runTest {
        val library = Library(Vocabulary.empty, refreshedTo = Vocabulary.empty)

        val state = AppStart(Account(AccountReadiness.Ready), library).begin()

        assertEquals(AppStart.Outcome.Setup(Unavailability.SERVER_SILENT), state)
    }

    /**
     * Настроенное приложение открывается без связи: словарь только растёт, снимок уже есть, и
     * свежесть его условием старта не является (PLAN J3).
     *
     * Красная проверка: пополнять словарь всегда — этот случай краснеет отказом сети.
     */
    @Test
    fun anAlreadySetUpAppStartsWithoutConnection() = runTest {
        val library = Library(Vocabulary(listOf(tablets), emptyList()), problem = Unavailability.NO_CONNECTION)

        val state = AppStart(Account(AccountReadiness.Ready), library).begin()

        assertEquals(AppStart.Outcome.Ready, state)
        assertEquals(0, library.refreshed)
    }
}

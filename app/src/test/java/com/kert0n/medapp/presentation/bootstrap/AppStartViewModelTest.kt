package com.kert0n.medapp.presentation.bootstrap

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.domain.account.DeviceAccount
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.domain.value.VocabularyLibrary
import com.kert0n.medapp.feature.account.AccountReplacement
import com.kert0n.medapp.feature.bootstrap.AppStart
import com.kert0n.medapp.fixture.DirectTransactions
import com.kert0n.medapp.fixture.FakeMedKits
import com.kert0n.medapp.fixture.MainDispatcherRule
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.awaiting
import com.kert0n.medapp.fixture.watching
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Решение «ключ утрачен» на экране 1 (PLAN G2, H3 №1): сценарий зовётся только из этого
 * состояния и только один раз на решение — два нажатия не заводят двух учёток. Что делает сам
 * сценарий, проверяет `AccountReplacementTest`.
 */
class AppStartViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    /** Учётка, которую нельзя открыть, пока человек не решил; после решения — новая, читаемая. */
    private class Account(private var readiness: AccountReadiness) : DeviceAccount {
        var replaced = 0
        val gate = CompletableDeferred<Unit>()
        override suspend fun ensure(): AccountReadiness = readiness
        override suspend fun replaceUnreadable(): AccountReadiness {
            replaced++
            gate.await()
            readiness = AccountReadiness.Ready
            return readiness
        }
    }

    private object KnownVocabulary : VocabularyLibrary {
        override suspend fun known(): Vocabulary = Vocabulary(listOf(TABLETS), emptyList())
        override suspend fun refresh(): Unavailability? = null
    }

    private fun viewModel(account: Account): AppStartViewModel {
        val start = AppStart(account, KnownVocabulary)
        val replacement = AccountReplacement(
            account, FakeMedKits(), DirectTransactions, start,
            Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneOffset.UTC)
        )
        return AppStartViewModel(start, replacement)
    }

    /** Пока человек не решил, экран стоит на «ключ утрачен», и никто ничего не заводит. */
    @Test
    fun aLostKeyWaitsForTheDecision() {
        val account = Account(AccountReadiness.KeyLost)
        val model = viewModel(account)

        watching(model.state) { it.awaiting { s -> s == AppStartState.KeyLost } }

        assertEquals(0, account.replaced)
    }

    /**
     * Решение — одно на два нажатия: пока первая попытка идёт, вторая не начинается. Иначе
     * нетерпеливое нажатие завело бы на сервере две учётки.
     */
    @Test
    fun decidingTwiceStartsOverOnce() {
        val account = Account(AccountReadiness.KeyLost)
        val model = viewModel(account)

        watching(model.state) { state ->
            state.awaiting { it == AppStartState.KeyLost }
            model.startOver()
            model.startOver()
            state.awaiting { it == AppStartState.Checking }
            account.gate.complete(Unit)
            state.awaiting { it == AppStartState.Ready }
        }

        assertEquals(1, account.replaced)
    }

    /** Из отказа настройки решения нет: «начать заново» без утраченного ключа ничего не зовёт. */
    @Test
    fun startingOverIsOnlyForALostKey() {
        val account = Account(AccountReadiness.NotReady(Unavailability.NO_CONNECTION))
        val model = viewModel(account)

        watching(model.state) { state ->
            state.awaiting { it is AppStartState.Setup }
            model.startOver()
            state.value
        }

        assertEquals(0, account.replaced)
    }
}

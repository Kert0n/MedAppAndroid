package com.kert0n.medapp.feature.bootstrap

import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.DeviceAccount
import com.kert0n.medapp.domain.value.VocabularyLibrary
import javax.inject.Inject

/**
 * Начало работы приложения: есть ли у устройства учётная запись и есть ли чем считать количества.
 * Пока обоих нет, показывать нечего — без пропуска нет ни словарей, ни справочника, и первый
 * запуск требует сети (PLAN C3). Экран настройки с повтором честнее пустого списка,
 * притворяющегося работающим приложением.
 *
 * Сценарий видит доменные порты, а не сеть: знакомство с сервером — такое же действие, как
 * остальные, и кто его выполняет по проводу, здесь не знают (PLAN H1).
 */
class AppStart @Inject constructor(
    private val account: DeviceAccount,
    private val vocabulary: VocabularyLibrary
) {

    suspend fun begin(): Outcome = when (val readiness = account.ensure()) {
        AccountReadiness.Ready -> vocabularyKnown()
        AccountReadiness.KeyLost -> Outcome.KeyLost
        is AccountReadiness.NotReady -> Outcome.Setup(readiness.reason)
    }

    /**
     * Словарь нужен, чтобы показать хоть одно количество; **свежесть** его — не условие старта.
     * Он только растёт, и уже настроенное приложение обязано открываться без связи (PLAN J3):
     * поэтому пополняется он, лишь когда не знаем ни одной единицы.
     */
    private suspend fun vocabularyKnown(): Outcome {
        if (vocabulary.known().knowsUnits) return Outcome.Ready
        vocabulary.refresh()?.let { return Outcome.Setup(it) }
        // Пополнили, а единиц всё равно нет: сервер ответил, но считать по-прежнему нечем.
        return if (vocabulary.known().knowsUnits) Outcome.Ready
        else Outcome.Setup(Unavailability.SERVER_SILENT)
    }

    /**
     * Чем кончилось начало. Случая три, и это исход **сценария**, а не состояние экрана: «идёт
     * проверка» сценарий не отвечает никогда — она длится, пока он не ответил, и живёт там, где её
     * видно, у состояния экрана (PLAN H1).
     */
    sealed interface Outcome {

        data object Ready : Outcome

        /** Сохранённое есть, но не открывается: заводить вторую учётку поверх нельзя (PLAN G2). */
        data object KeyLost : Outcome

        /** Настройка не прошла, и названо почему; повтор осмыслен по правилам самой причины. */
        data class Setup(val reason: Unavailability) : Outcome
    }
}

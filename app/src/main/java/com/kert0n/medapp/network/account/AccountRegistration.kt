package com.kert0n.medapp.network.account

import com.kert0n.medapp.di.RegistrationToken
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Первичная настройка устройства (PLAN B1, G2): учётные данные **придумывает устройство**,
 * записывает их и только потом просит сервер их запомнить. Порядок именно такой: пока данных нет
 * на устройстве, отправлять нечего, а потерянный ответ безопасен — повтор идёт теми же данными, и
 * сервер отвечает «такая уже есть». Своя ли это учётка, показывает пропуск по тем же данным.
 *
 * Нечитаемую учётку поверх не перерегистрируют — это решение человека, потому что брони на старой
 * уже не снять; приняв его, [replaceUnreadable] стирает нечитаемое — и пропуск прежней учётки
 * вместе с ним ([AccessTokens.forget]): кто учётку пишет, тот и говорит, что она сменилась, — и
 * знакомится заново. Регистрация одна на всех вызывающих: два экрана, спросившие одновременно, не
 * заведут двух учёток.
 */
@Singleton
class AccountRegistration @Inject constructor(
    private val api: MedAppApi,
    private val credentials: CredentialSource,
    @RegistrationToken private val registrationToken: String,
    private val tokens: AccessTokens
) : AccountReclaim {

    sealed interface Outcome {

        /** Учётка есть и сервер её знает — только что записана или уже была. */
        data object Ready : Outcome

        /** Сохранённое не открывается: спросить человека, а не заводить новую молча. */
        data object Unreadable : Outcome

        /**
         * Придуманные данные не легли на устройство. На сервере при этом ничего не заведено:
         * терять нечего, и повторить настройку можно.
         */
        data object NotStored : Outcome

        /** Сервер учётку не принял: ввод, токен сборки, отсутствие связи или неизвестный исход. */
        data class Failed(val failure: ApiFailure) : Outcome
    }

    private val mutex = Mutex()

    suspend fun ensure(): Outcome = mutex.withLock { known() }

    /**
     * Человек решил начать с новой учёткой (PLAN G2). Стирается только **нечитаемая**: у придуманной
     * и не подтверждённой сервер, возможно, уже есть, и повтор идёт теми же данными; читаемую не
     * трогают вовсе. Не удалось стереть — на устройстве по-прежнему нечитаемое, и завести поверх
     * него нечего.
     */
    suspend fun replaceUnreadable(): Outcome = mutex.withLock {
        if (credentials.read() == StoredAccount.Unreadable) {
            if (credentials.forget() == CredentialsSaved.LOST) return@withLock Outcome.NotStored
            // Учётки больше нет — и её пропуска тоже: сервер принял бы его ещё часы, но он чужой
            // теперь (C1 «Пропуск — учётки»).
            tokens.forget()
        }
        known()
    }

    /**
     * Сервер забыл учётку: те же данные регистрируются заново тем же путём, что недописанная
     * учётка (201 — наша снова; 409 — проверка пропуском). Сохранённое не трогается: если процесс
     * умрёт посередине, следующий 401 придёт сюда же, и 409 с пропуском подтвердит, что учётка наша.
     *
     * Без [mutex]: возврат зовёт выдача пропуска под своим замком, а [replaceUnreadable] под этим
     * замком зовёт `AccessTokens.forget` — взятые в обратном порядке, два замка ждали бы друг друга.
     * Одна регистрация на всплеск держится замком выдачи; совпавшая с ней настройка регистрирует те
     * же данные и получает 409, который разрешается пропуском.
     */
    override suspend fun reclaim(account: AccountCredentials): Boolean = register(account) == Outcome.Ready

    private suspend fun known(): Outcome =
        when (val stored = credentials.read()) {
            is StoredAccount.Present -> Outcome.Ready
            StoredAccount.Unreadable -> Outcome.Unreadable
            // Записаны, но сервер о них не сказал: повтор идёт теми же данными.
            is StoredAccount.Pending -> register(stored.credentials)
            StoredAccount.Absent -> {
                val invented = AccountCredentials.random()
                when (credentials.save(invented)) {
                    CredentialsSaved.SAVED -> register(invented)
                    CredentialsSaved.LOST -> Outcome.NotStored
                }
            }
        }

    private suspend fun register(account: AccountCredentials): Outcome =
        when (val registered = api.register(account, registrationToken)) {
            is ApiResult.Success -> confirmed()
            is ApiResult.Failure ->
                if (registered.failure == ApiFailure.Conflict) ours(account)
                else Outcome.Failed(registered.failure)
        }

    /**
     * Логин занят: наш ли. Пропуск по тем же данным выдан — учётка наша и запись подтверждается;
     * учётные данные не приняты — логин чужой, и это отказ, а не повод придумывать новые поверх.
     * Прочие отказы говорят не о принадлежности, а о связи, и повторяются позже.
     */
    private suspend fun ours(account: AccountCredentials): Outcome =
        when (val issued = api.token(account)) {
            is ApiResult.Success -> confirmed()
            is ApiResult.Failure ->
                if (issued.failure == ApiFailure.Unauthorized) Outcome.Failed(ApiFailure.Conflict)
                else Outcome.Failed(issued.failure)
        }

    /**
     * Подтверждение — тоже запись, и она может не лечь. Учётка при этом рабочая: следующая
     * настройка повторит регистрацию теми же данными и получит `409`, а не заведёт вторую.
     */
    private suspend fun confirmed(): Outcome {
        credentials.confirm()
        return Outcome.Ready
    }
}

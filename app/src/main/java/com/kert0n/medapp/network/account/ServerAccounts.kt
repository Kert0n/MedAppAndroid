package com.kert0n.medapp.network.account

import com.kert0n.medapp.di.RegistrationToken
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountCredentials
import com.kert0n.medapp.feature.account.AccountServer
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Знакомство с сервером на проводе: регистрация придуманной учётки и проверка, наша ли она, когда
 * логин занят (`409` — пропуск по тем же данным). Тот же звонок возвращает серверу забытую учётку
 * ([AccountReclaim]) — сохранённое на устройстве при этом не пишется, данные те же.
 */
@Singleton
class ServerAccounts @Inject constructor(
    private val api: MedAppApi,
    @RegistrationToken private val registrationToken: String,
    private val tokens: AccessTokens
) : AccountServer, AccountReclaim {

    override suspend fun register(credentials: AccountCredentials): AccountServer.Registration =
        when (val registered = api.register(credentials, registrationToken)) {
            is ApiResult.Success -> AccountServer.Registration.Known
            is ApiResult.Failure ->
                if (registered.failure == ApiFailure.Conflict) ours(credentials)
                else AccountServer.Registration.Failed(registered.failure.asUnavailability())
        }

    override suspend fun forgetAccess() {
        tokens.forget()
    }

    /**
     * Без замка регистрации: возврат зовёт выдача пропуска под своим замком, а замена нечитаемой
     * под замком регистрации зовёт [forgetAccess] — взятые в обратном порядке, два замка ждали бы
     * друг друга. Совпавшая с возвратом настройка регистрирует те же данные и получает `409`.
     */
    override suspend fun reclaim(account: AccountCredentials): Boolean =
        register(account) == AccountServer.Registration.Known

    /**
     * Логин занят: наш ли. Пропуск по тем же данным выдан — учётка наша; учётные данные не
     * приняты — логин чужой, и это отказ, а не повод придумывать новые поверх. Прочие отказы
     * говорят не о принадлежности, а о связи, и повторяются позже.
     */
    private suspend fun ours(account: AccountCredentials): AccountServer.Registration =
        when (val issued = api.token(account)) {
            is ApiResult.Success -> AccountServer.Registration.Known
            is ApiResult.Failure -> AccountServer.Registration.Failed(
                if (issued.failure == ApiFailure.Unauthorized) ApiFailure.Conflict.asUnavailability()
                else issued.failure.asUnavailability()
            )
        }
}

/**
 * Отказ сети словами домена. Обрыв до сервера — это отсутствие связи; отказ в пропуске — «нас не
 * приняли», и повтор тем же не поможет; всё остальное — сервер промолчал или ответил не по
 * контракту, и повторить стоит позже.
 */
internal fun ApiFailure.asUnavailability(): Unavailability = when (this) {
    ApiFailure.Unavailable -> Unavailability.NO_CONNECTION
    ApiFailure.Unauthorized, ApiFailure.RegistrationRefused -> Unavailability.SERVER_REFUSED_US
    else -> Unavailability.SERVER_SILENT
}

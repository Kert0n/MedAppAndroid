package com.kert0n.medapp.network.account

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.domain.account.DeviceAccount
import com.kert0n.medapp.network.server.ApiFailure
import javax.inject.Inject

/**
 * Учётная запись устройства на этом сервере: выполняет доменный порт тем, что умеет сеть.
 * Механику знакомства целиком держит [AccountRegistration]; здесь — перевод её исходов на язык
 * домена, потому что коды ответа за границу сети не уходят (PLAN B5, H1).
 */
class ServerDeviceAccount @Inject constructor(
    private val registration: AccountRegistration
) : DeviceAccount {

    override suspend fun ensure(): AccountReadiness = registration.ensure().asReadiness()

    override suspend fun replaceUnreadable(): AccountReadiness = registration.replaceUnreadable().asReadiness()

    private fun AccountRegistration.Outcome.asReadiness(): AccountReadiness = when (val outcome = this) {
        AccountRegistration.Outcome.Ready -> AccountReadiness.Ready
        AccountRegistration.Outcome.Unreadable -> AccountReadiness.KeyLost
        // Не записались — значит на сервере ничего нет: исход настройки, а не сбой (PLAN G2).
        AccountRegistration.Outcome.NotStored -> AccountReadiness.NotReady(Unavailability.DEVICE_STORAGE)
        is AccountRegistration.Outcome.Failed -> AccountReadiness.NotReady(outcome.failure.asUnavailability())
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

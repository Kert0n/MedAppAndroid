package com.kert0n.medapp.feature.account

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountCredentials
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.domain.account.DeviceAccount
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Первичная настройка устройства (PLAN B1, G2): учётные данные **придумывает устройство**,
 * записывает их и только потом просит сервер их запомнить. Порядок именно такой: пока данных нет
 * на устройстве, отправлять нечего, а потерянный ответ безопасен — повтор идёт теми же данными.
 * Хранит учётку платформа ([CredentialSource]), знакомит с сервером сеть ([AccountServer]).
 *
 * Нечитаемую учётку поверх не перерегистрируют — это решение человека, потому что брони на старой
 * уже не снять; приняв его, [replaceUnreadable] стирает нечитаемое и пропуск прежней учётки вместе
 * с ним — и знакомится заново. Регистрация одна на всех вызывающих: два экрана, спросившие
 * одновременно, не заведут двух учёток.
 */
@Singleton
class AccountRegistration @Inject constructor(
    private val credentials: CredentialSource,
    private val server: AccountServer
) : DeviceAccount {

    private val mutex = Mutex()

    override suspend fun ensure(): AccountReadiness = mutex.withLock { known() }

    /**
     * Стирается только **нечитаемая** учётка: у придуманной и не подтверждённой сервер, возможно,
     * уже есть, и повтор идёт теми же данными; читаемую не трогают вовсе. Не удалось стереть — на
     * устройстве по-прежнему нечитаемое, и завести поверх него нечего.
     */
    override suspend fun replaceUnreadable(): AccountReadiness = mutex.withLock {
        if (credentials.read() == StoredAccount.Unreadable) {
            if (credentials.forget() == CredentialsSaved.LOST) return@withLock notStored
            server.forgetAccess()
        }
        known()
    }

    private suspend fun known(): AccountReadiness =
        when (val stored = credentials.read()) {
            is StoredAccount.Present -> AccountReadiness.Ready
            StoredAccount.Unreadable -> AccountReadiness.KeyLost
            // Записаны, но сервер о них не сказал: повтор идёт теми же данными.
            is StoredAccount.Pending -> register(stored.credentials)
            StoredAccount.Absent -> {
                val invented = AccountCredentials.random()
                when (credentials.save(invented)) {
                    CredentialsSaved.SAVED -> register(invented)
                    CredentialsSaved.LOST -> notStored
                }
            }
        }

    /**
     * Подтверждение — тоже запись, и она может не лечь. Учётка при этом рабочая: следующая
     * настройка повторит регистрацию теми же данными, а не заведёт вторую.
     */
    private suspend fun register(account: AccountCredentials): AccountReadiness =
        when (val registered = server.register(account)) {
            AccountServer.Registration.Known -> {
                credentials.confirm()
                AccountReadiness.Ready
            }
            is AccountServer.Registration.Failed -> AccountReadiness.NotReady(registered.reason)
        }

    /** Не записались — значит, на сервере ничего нет: исход настройки, а не сбой (PLAN G2). */
    private val notStored = AccountReadiness.NotReady(Unavailability.DEVICE_STORAGE)
}

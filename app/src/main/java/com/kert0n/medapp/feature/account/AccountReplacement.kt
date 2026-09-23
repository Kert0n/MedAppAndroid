package com.kert0n.medapp.feature.account

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.account.AccountReadiness
import com.kert0n.medapp.domain.account.DeviceAccount
import com.kert0n.medapp.feature.bootstrap.AppStart
import java.time.Clock
import javax.inject.Inject

/**
 * Человек решил начать с новой учёткой, потому что старую не открыть (PLAN G2). Зовёт его только
 * подтверждение на экране — сценарий сам ничего не решает: учётку, которая читается, он не трогает
 * и просто начинает работу заново.
 *
 * Порядок — сначала местное, потом сеть. Старая учётка мертва независимо от связи: общие полки
 * уходят утратой доступа той же дверью, что «полки не стало в снимке», их очередь закрывается
 * «отправлять некуда», брони остаются у других — снимать их некому. Местные полки, курсы, приёмы и
 * записи о коробках целы. Потом — знакомство заново обычной регистрацией; отказ сети после местной
 * части ничего не портит: повтор идёт через [AppStart] теми же придуманными данными, а серверных
 * полок к тому времени уже нет.
 */
class AccountReplacement @Inject constructor(
    private val account: DeviceAccount,
    private val abandonment: ServerAbandonment,
    private val appStart: AppStart,
    private val clock: Clock
) {

    suspend fun decide(): AppStart.Outcome {
        if (account.ensure() != AccountReadiness.KeyLost) return appStart.begin()
        abandonment.abandon(clock.instant())
        return when (val replaced = account.replaceUnreadable()) {
            AccountReadiness.Ready -> appStart.begin()
            // Нечитаемое после стирания — уже не про учётку, а про хранилище устройства.
            AccountReadiness.KeyLost -> AppStart.Outcome.Setup(Unavailability.DEVICE_STORAGE)
            is AccountReadiness.NotReady -> AppStart.Outcome.Setup(replaced.reason)
        }
    }
}

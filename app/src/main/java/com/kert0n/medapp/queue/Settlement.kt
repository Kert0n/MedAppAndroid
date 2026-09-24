package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Что исход доставки значит для базы — переход строки операции и список эффектов. Решает
 * очередь, там, где живёт доставка; хранение применяет список одной транзакцией и ничего не
 * толкует (PLAN E3, F5). Эффекты применяются только если [transition] изменил строку: закрытие
 * одно, и у второго закрытия следствий нет.
 */
class Settlement(val transition: Transition, effects: List<Effect> = emptyList()) {

    /** Своя копия: список, оставшийся у вызывающего, менял бы уже решённое. */
    val effects: List<Effect> = effects.toList()

    /** Что становится со строкой операции. */
    sealed interface Transition {

        /**
         * Операция закрыта — ровно три случая, как и закрытых статусов: применена, отказана —
         * и только у отказа есть причина, почему сервер делать не будет (PLAN E2), — или потеряла
         * доступ. Закрытие в незакрытый статус и отказ без причины не выражаются.
         */
        sealed interface Close : Transition {
            val status: SyncOperationStatus
            val refusalReason: RefusalReason? get() = null

            data object Applied : Close {
                override val status: SyncOperationStatus get() = SyncOperationStatus.APPLIED
            }

            data class Refused(override val refusalReason: RefusalReason) : Close {
                override val status: SyncOperationStatus get() = SyncOperationStatus.REFUSED
            }

            data object AccessLost : Close {
                override val status: SyncOperationStatus get() = SyncOperationStatus.ACCESS_LOST
            }
        }

        /** Запрос сброшен, операция снова ждёт под тем же номером; факт о запросе умирает с ним. */
        data class Reprepare(val lastError: String, val notBefore: Instant? = null) : Transition

        /** Операция снова ждёт тем же запросом; попытка и неизвестный исход — как сказал [Delivery.Retry]. */
        data class Retry(
            val lastError: String,
            val attempted: Boolean,
            val outcomeUnknown: Boolean,
            val notBefore: Instant? = null
        ) : Transition
    }

    /** Что ещё меняется в базе вместе с переходом. */
    sealed interface Effect {

        /** Разрешённый снимок ложится поверх подтверждённого остатка и броней; старее нынешнего — нет. */
        data class LayDown(val snapshot: PackageSnapshot) : Effect

        /**
         * Коробки у нас больше нет — сервер подтвердил её конец либо утрату доступа. Истории у
         * коробки нет, и чем конец вызван, хранение не различает: оно просит у пачки её переход
         * (PLAN D3, D7).
         */
        data class PackageEnded(val packageId: Uuid) : Effect

        /**
         * Унесённую домой коробку сервер больше не знает: у нас она просто местная — без версий и
         * броней. Это не конец коробки: она цела и лежит у человека (PLAN E6).
         */
        data class Withdrawn(val packageId: Uuid) : Effect

        /**
         * Унести домой не вышло: коробка возвращается на полку [medKitId], откуда её взяли, — раньше,
         * чем ляжет ответ сервера, иначе снимку не на что было бы лечь (PLAN E1, E6).
         */
        data class Returned(val packageId: Uuid, val medKitId: Uuid) : Effect

        /**
         * Полку разобрали: сервер согласился, и теперь её содержимое либо переезжает в
         * [transferTo], либо уходит совсем, а следом уходит и сама строка аптечки (PLAN E6).
         */
        data class MedKitDismantled(val medKitId: Uuid, val transferTo: Uuid?) : Effect

        /** Из полки вышли: её коробки нам больше не видны, а сама она остаётся остальным (E6). */
        data class MedKitLeft(val medKitId: Uuid) : Effect

        /**
         * Сервер завёл полку: она существует и у него. Пометку это не снимает — содержимое едет
         * своими командами, и решение «сделать полку общей» доведено, когда закрылись они все
         * ([Settled]); до тех пор приглашений полка не выдаёт (PLAN D2, E5).
         */
        data class MedKitPublished(val medKitId: Uuid) : Effect

        /** Учёт расхода у приёма, который поставил эту операцию. */
        data class Account(val accounting: IntakeAccounting) : Effect

        /**
         * Команда закрыта — применена или нет, — и решение, ради которого она стояла, больше не
         * ждёт. Пометка держится на вещи, пока у неё есть незакрытая команда: последняя закрытая
         * снимает её (PLAN E1). Применённый конец строки не оставляет, и снимать тогда нечего;
         * неразрешимый сбой возвращает вещь в оборот, и человек решает заново. Кого касается,
         * хранение знает по строке операции — ей же принадлежат пачка и полка команды.
         */
        data object Settled : Effect

        /**
         * Незакрытые зависимые закрываются [close] — тем же переходом, каким закрывается своя
         * операция, а не статусом и колонками, которые каскад сочинял бы сам, — их приёмы
         * получают [accounting]; и так до конца цепочки. Отказ без причины и закрытие в
         * незакрытый статус здесь так же невыразимы, как у [Transition.Close].
         */
        data class Cascade(val close: Transition.Close, val accounting: IntakeAccounting) : Effect
    }
}

/**
 * Исход доставки команды [command] — в переход и эффекты. Чистая функция: проверяется без базы,
 * а в хранении не остаётся ветвления по видам доставки (PLAN E3).
 */
fun Delivery.settlement(command: SyncCommand): Settlement = when (this) {
    is Delivery.Applied -> Settlement(
        Settlement.Transition.Close.Applied,
        listOf(Settlement.Effect.Account(IntakeAccounting.REMOTE_APPLIED)) + state.effects(command) +
            command.appliedToTheShelf() + Settlement.Effect.Settled
    )
    is Delivery.Stale -> Settlement(
        Settlement.Transition.Reprepare("устарело: ${snapshot.sync.version}", notBefore),
        listOf(Settlement.Effect.LayDown(snapshot))
    )
    is Delivery.Refused -> Settlement(
        Settlement.Transition.Close.Refused(reason),
        listOf(Settlement.Effect.Account(IntakeAccounting.REMOTE_REFUSED)) + command.returned() + state.effects(command) +
            Settlement.Effect.Cascade(Settlement.Transition.Close.Refused(RefusalReason.SUPERSEDED), IntakeAccounting.REMOTE_REFUSED) +
            Settlement.Effect.Settled
    )
    is Delivery.Retry -> Settlement(
        Settlement.Transition.Retry(error, attempted, outcomeUnknown, notBefore)
    )
    Delivery.AccessLost -> Settlement(
        Settlement.Transition.Close.AccessLost,
        listOf(Settlement.Effect.Account(IntakeAccounting.REMOTE_REFUSED)) +
            listOfNotNull((command as? PackageSyncCommand)?.gone()) +
            Settlement.Effect.Cascade(Settlement.Transition.Close.AccessLost, IntakeAccounting.REMOTE_REFUSED) +
            Settlement.Effect.Settled
    )
}

/** Истина по пачке после закрытия — что положить: снимок, «пачки нет» либо ничего. */
private fun PackageState.effects(command: SyncCommand): List<Settlement.Effect> = when (this) {
    is PackageState.Present -> listOf(Settlement.Effect.LayDown(snapshot))
    // Пачки нет — либо команда своё сделала, а коробка ушла туда, где нас нет: у нас она кончается.
    PackageState.Gone, PackageState.Elsewhere -> listOfNotNull((command as? PackageSyncCommand)?.gone())
    PackageState.None -> emptyList()
}

/**
 * Коробки нет на сервере. Для унесённой домой это и было желаемым — она остаётся у нас местной;
 * для остальных это конец (PLAN D3, E6).
 */
private fun PackageSyncCommand.gone(): Settlement.Effect =
    if (this is PackageSyncCommand.Withdraw) Settlement.Effect.Withdrawn(packageId)
    else Settlement.Effect.PackageEnded(packageId)

/**
 * Отказ возвращает коробку туда, откуда её взяли: унос домой — на полку, с которой снимали;
 * создание — на полку, с которой её переложили. Полка есть, а коробка на сервере не завелась —
 * значит она осталась там, где и была, и вид у неё должен быть тот же (PLAN E6). У прочих отказов
 * возвращать нечего: коробка никуда не переезжала.
 */
private fun SyncCommand.returned(): List<Settlement.Effect> = when {
    this is PackageSyncCommand.Withdraw -> listOf(Settlement.Effect.Returned(packageId, fromMedKitId))
    this is PackageSyncCommand.Create && fromMedKitId != null ->
        listOf(Settlement.Effect.Returned(packageId, fromMedKitId))
    else -> emptyList()
}

/**
 * Что применение команды значит для самой полки. У команд пачки такого следствия нет: они об одной
 * коробке, а разбор и выход меняют полку целиком (PLAN E6).
 */
private fun SyncCommand.appliedToTheShelf(): List<Settlement.Effect> = when (this) {
    is MedKitSyncCommand.Publish -> listOf(Settlement.Effect.MedKitPublished(medKitId))
    is MedKitSyncCommand.Delete -> listOf(Settlement.Effect.MedKitDismantled(medKitId, transferTo))
    is MedKitSyncCommand.Leave -> listOf(Settlement.Effect.MedKitLeft(medKitId))
    else -> emptyList()
}

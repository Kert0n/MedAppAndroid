package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.intake.IntakeAccounting
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
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

        /** Операция закрыта — применена, отказана или потеряла доступ; [lastError] — причина отказа. */
        data class Close(val status: SyncOperationStatus, val lastError: String? = null) : Transition {
            init {
                require(status.isClosed) { "закрытие ведёт в закрытое состояние, а не в $status" }
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
         * Коробки у нас больше нет. Чем это объясняется в истории, называет [ending]: решение
         * выбросить — утилизацией всего остатка, пересчёт в ноль — пересчётом, расход — ничем
         * (приём и есть учётная запись о себе), утрата доступа — последним виденным остатком
         * (PLAN D7, H6). Хранение получает название и просит у пачки её переход.
         */
        data class PackageEnded(val packageId: Uuid, val ending: Ending) : Effect

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

        /** Чем кончилась коробка по ответу сервера — ровно то, что различает её след. */
        enum class Ending { THROWN_OUT, RECOUNTED, CONSUMED, ACCESS_LOST }

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

        /** Незакрытые зависимые закрываются [status], их приёмы получают [accounting]; и так до конца цепочки. */
        data class Cascade(val status: SyncOperationStatus, val accounting: IntakeAccounting) : Effect
    }
}

/**
 * Исход доставки команды [command] — в переход и эффекты. Чистая функция: проверяется без базы,
 * а в хранении не остаётся ветвления по видам доставки (PLAN E3).
 */
fun Delivery.settlement(command: SyncCommand): Settlement = when (this) {
    is Delivery.Applied -> Settlement(
        Settlement.Transition.Close(SyncOperationStatus.APPLIED),
        listOf(Settlement.Effect.Account(IntakeAccounting.REMOTE_APPLIED)) + state.effects(command) +
            command.appliedToTheShelf() + Settlement.Effect.Settled
    )
    is Delivery.Stale -> Settlement(
        Settlement.Transition.Reprepare("устарело: ${snapshot.sync.version}", notBefore),
        listOf(Settlement.Effect.LayDown(snapshot))
    )
    is Delivery.Refused -> Settlement(
        Settlement.Transition.Close(SyncOperationStatus.REFUSED, reason.name),
        listOf(Settlement.Effect.Account(IntakeAccounting.REMOTE_REFUSED)) + command.returned() + state.effects(command) +
            Settlement.Effect.Cascade(SyncOperationStatus.REFUSED, IntakeAccounting.REMOTE_REFUSED) +
            Settlement.Effect.Settled
    )
    is Delivery.Retry -> Settlement(
        Settlement.Transition.Retry(error, attempted, outcomeUnknown, notBefore)
    )
    Delivery.AccessLost -> Settlement(
        Settlement.Transition.Close(SyncOperationStatus.ACCESS_LOST),
        listOf(Settlement.Effect.Account(IntakeAccounting.REMOTE_REFUSED)) +
            listOfNotNull((command as? PackageSyncCommand)?.gone(Settlement.Effect.Ending.ACCESS_LOST)) +
            Settlement.Effect.Cascade(SyncOperationStatus.ACCESS_LOST, IntakeAccounting.REMOTE_REFUSED) +
            Settlement.Effect.Settled
    )
}

/** Истина по пачке после закрытия — что положить: снимок, «пачки нет» либо ничего. */
private fun PackageState.effects(command: SyncCommand): List<Settlement.Effect> = when (this) {
    is PackageState.Present -> listOf(Settlement.Effect.LayDown(snapshot))
    PackageState.Gone -> listOfNotNull((command as? PackageSyncCommand)?.let { it.gone(it.endsAs()) })
    // Команда своё сделала, а коробка ушла туда, где нас нет: у нас она кончается утратой доступа.
    PackageState.Elsewhere -> listOfNotNull((command as? PackageSyncCommand)?.gone(Settlement.Effect.Ending.ACCESS_LOST))
    PackageState.None -> emptyList()
}

/**
 * Коробки нет на сервере. Для унесённой домой это и было желаемым — она остаётся у нас местной;
 * для остальных это конец, и [ending] называет его след (PLAN D7, E6).
 */
private fun PackageSyncCommand.gone(ending: Settlement.Effect.Ending): Settlement.Effect =
    if (this is PackageSyncCommand.Withdraw) Settlement.Effect.Withdrawn(packageId)
    else Settlement.Effect.PackageEnded(packageId, ending)

/** Отказ унести домой возвращает коробку на прежнюю полку; у прочих отказов возвращать нечего. */
private fun SyncCommand.returned(): List<Settlement.Effect> =
    if (this is PackageSyncCommand.Withdraw) listOf(Settlement.Effect.Returned(packageId, fromMedKitId)) else emptyList()

/**
 * Чем кончилась коробка, у которой сервер подтвердил «её нет»: каждая команда знает, зачем её
 * посылали, и след истории берётся отсюда, а не выдумывается хранением (PLAN D7, H6).
 */
private fun PackageSyncCommand.endsAs(): Settlement.Effect.Ending = when (this) {
    is PackageSyncCommand.Delete -> Settlement.Effect.Ending.THROWN_OUT
    is PackageSyncCommand.CorrectStock -> Settlement.Effect.Ending.RECOUNTED
    else -> Settlement.Effect.Ending.CONSUMED
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

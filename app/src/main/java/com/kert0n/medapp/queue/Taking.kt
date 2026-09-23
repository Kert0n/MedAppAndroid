package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.prepare
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Взятие операции в отправку: решение очереди, записанное хранением. Подготовку по свежему
 * состоянию делает очередь, а не хранилище, — хранение только кладёт снимок, отдаёт пачку и
 * пишет решённое; посылку по решению собирает упаковка ([Packing]) (PLAN E2, E3, F5).
 */
class Taking @Inject constructor(
    private val storage: QueueStorage,
    private val transactions: Transactions,
    private val packing: Packing
) {

    /**
     * Взятие в отправку — одна транзакция: свежее состояние ложится в базу первым, предусловия
     * берутся у пачки после этого — версии, подтверждённый остаток и своя бронь, то, что у сервера
     * **сейчас**, а не то, что устройство видело когда-то (PLAN E2, E3). Собранный запрос не
     * пересобирается: повтор с неизвестным исходом идёт тем же. Подготовка может отказать или
     * найти желаемое уже наступившим — тогда операция закрывается здесь же, той же транзакцией.
     * `null` — операции нет, она закрыта или ждёт с квитанцией в руках.
     */
    suspend fun take(id: Uuid, fresh: PackageSnapshot?, at: Instant): Take? = transactions.run {
        val operation = storage.operation(id)
        if (operation == null || operation.status.isClosed || operation.awaitsApplication) return@run null
        val command = operation.command
        // Унесённую домой коробку человек мог уже выбросить у себя. Серверу она всё равно должна
        // исчезнуть, а свежий снимок, положенный в базу, завёл бы её обратно: он даёт только версию.
        val carriedAway = command is PackageSyncCommand.Withdraw && storage.knownPackage(command.packageId) == null
        if (!carriedAway) fresh?.let { storage.layDown(it, at) }
        val sending = if (operation.prepared == null) {
            val request = when (command) {
                // Снимают по версии полки, а её подтверждённое число запоминается в запросе: из него
                // и из сделанного дома сложится остаток, когда полка ответит (PLAN E6).
                is PackageSyncCommand.Withdraw if fresh != null ->
                    packing.pack(operation.id, command, fresh.sync, Preparation.Send(confirmed = fresh.pack.quantity, mine = null), at)
                is PackageSyncCommand -> {
                    val known = storage.knownPackage(command.packageId)
                        ?: return@run closedByPreparation(operation, Delivery.AccessLost, at)
                    when (val prepared = command.prepare(known.pack, known.sync)) {
                        is Preparation.Send -> packing.pack(operation.id, command, known.sync, prepared, at)
                        is Preparation.Refuse ->
                            return@run closedByPreparation(operation, Delivery.Refused(prepared.reason, PackageState.None), at)
                        Preparation.AlreadyApplied -> return@run closedByPreparation(operation, Delivery.Applied(PackageState.None), at)
                    }
                }
                is MedKitSyncCommand -> packing.pack(command, at)
                else -> command.unknownRoot()
            }
            operation.taken(request)
        } else {
            operation.resent()
        } ?: return@run null
        storage.write(sending, was = operation.status)
        Take.Sending(sending)
    }

    /** Подготовка закрыла операцию сама: истина по пачке уже в базе — она только что легла свежим снимком. */
    private suspend fun closedByPreparation(operation: SyncOperation, delivery: Delivery, at: Instant): Take {
        storage.settle(operation.id, delivery.settlement(operation.command), at)
        return Take.Closed(delivery)
    }
}

package com.kert0n.medapp.storage.operation

import androidx.room.withTransaction
import com.kert0n.medapp.domain.course.PackageFollowing
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.domain.pack.PackageAfter
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.feature.readThisTransaction
import com.kert0n.medapp.queue.QueueStorage
import com.kert0n.medapp.queue.QueuedCommand
import com.kert0n.medapp.queue.Receipt
import com.kert0n.medapp.queue.Settlement
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationState
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.medkit.MedKitDao
import com.kert0n.medapp.storage.medkit.loseAccess
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.applySnapshot
import com.kert0n.medapp.storage.pack.end
import com.kert0n.medapp.storage.pack.save
import com.kert0n.medapp.storage.value.VocabularyDao
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Порт очереди в Room — [QueueStorage] для работника. Транзакции очереди принадлежат ей: заморозка
 * запроса вместе с переводом в отправку, применение исхода вместе со всеми его эффектами (PLAN F5).
 * Что исход значит, решено в очереди ([Settlement]); здесь эффекты только применяются, и
 * ветвления по видам доставки нет. Строки очереди для тех, кто их ставит и читает, —
 * [SyncOperationRoomRepository].
 */
class QueueRoomStorage @Inject constructor(
    private val database: MedAppDatabase,
    private val queue: SyncOperationDao,
    private val packages: PackageDao,
    private val intakes: IntakeDao,
    private val medKits: MedKitDao,
    private val courses: CourseDao,
    private val vocabulary: VocabularyDao,
    /**
     * Курс следует за коробкой — прикладной владелец реакции (PLAN D5); здесь ему дают транзакцию.
     * Лениво, потому что он сам ставит команды через эту же очередь, и граф иначе замкнулся бы в кольцо.
     */
    private val following: Provider<PackageFollowing>
) : QueueStorage {

    /** Room сообщает об изменении таблицы после коммита — то, что outbox и должен услышать; первое значение — наблюдатель встал. */
    override fun changes(): Flow<Unit> =
        database.invalidationTracker.createFlow("sync_operations", emitInitialState = true).map { }

    override suspend fun enqueue(queued: QueuedCommand, shelf: Uuid, at: Instant): SyncOperation =
        queue.enqueue(queued.id, queued.command, at, queued.groupId, queued.dependsOn, medKitId = shelf)

    override suspend fun ready(now: Instant): List<StoredSyncOperation> = database.withTransaction {
        val words = vocabulary.snapshot()
        queue.ready(now).map { it.toDomain(words) }
    }

    override suspend fun nextDueAt(now: Instant): Instant? = queue.nextDueAt(now)

    override suspend fun medKit(id: Uuid): MedKitRef? = medKits.find(id)?.toRef()

    override suspend fun operation(id: Uuid): SyncOperation? = operationOf(id)

    override suspend fun knownPackage(id: Uuid): PackageSnapshot? = database.withTransaction {
        packages.find(id)?.let { row -> PackageSnapshot(row.toDomain(vocabulary.snapshot()), row.pack.syncState()) }
    }

    override suspend fun layDown(snapshot: PackageSnapshot, at: Instant) = database.withTransaction {
        layDown(snapshot, at, carried = null)
    }

    override suspend fun write(operation: SyncOperation, was: SyncOperationStatus) = database.withTransaction {
        save(operation, was)
    }

    override suspend fun unclosedOfMedKit(medKitId: Uuid): List<StoredSyncOperation> = database.withTransaction {
        val words = vocabulary.snapshot()
        queue.unclosedRowsOfMedKit(medKitId).map { it.toDomain(words) }
    }

    override suspend fun answered(id: Uuid, answer: Receipt, at: Instant) = database.withTransaction {
        val operation = operationOf(id) ?: return@withTransaction
        val answered = operation.answered(answer, at) ?: return@withTransaction
        save(answered, was = operation.status)
    }

    override suspend fun defer(id: Uuid, reason: String, at: Instant, notBefore: Instant) = database.withTransaction {
        val operation = operationOf(id) ?: return@withTransaction
        val deferred = operation.deferred(reason, at, notBefore) ?: return@withTransaction
        save(deferred, was = operation.status)
    }

    /** Операция после перехода — в строку, которую прочитали этой же транзакцией (F5). */
    private suspend fun save(operation: SyncOperation, was: SyncOperationStatus) =
        (queue.save(operation.id, operation.state, operation.prepared?.toStorageColumns(), was) == 1).readThisTransaction("операция")

    /**
     * Переход и его эффекты одной транзакцией. Что переход меняет и из какого статуса возможен,
     * решает состояние операции ([SyncOperationState]); неприменимый — `null`, и следствий у него
     * нет: закрытая второй раз не закрывается. Строка, которую нечем прочитать, несёт то же
     * состояние и закрывается тем же переходом; повторить или переподготовить её нечем.
     */
    override suspend fun settle(id: Uuid, settlement: Settlement, at: Instant) = database.withTransaction {
        val row = queue.find(id) ?: return@withTransaction
        val stored = row.toDomain(vocabulary.snapshot())
        val after: SyncOperationState = when (val transition = settlement.transition) {
            is Settlement.Transition.Close -> stored.state.closed(transition, at)
            is Settlement.Transition.Reprepare ->
                (stored as? StoredSyncOperation.Readable)?.state?.reprepared(transition.lastError, at, transition.notBefore)
            is Settlement.Transition.Retry -> (stored as? StoredSyncOperation.Readable)?.state?.retried(
                transition.lastError, at, transition.attempted, transition.outcomeUnknown, transition.notBefore
            )
        } ?: return@withTransaction
        // Переподготовка сбрасывает запрос; остальные переходы его не трогают.
        val prepared = row.operation.prepared.takeIf { after.hasRequest }
        // Пишется та строка, которую прочитали, — её статус **как он лежит**: у порченой строки
        // прочитанное состояние могло взять другой статус, а условие записи говорит о колонке.
        (queue.save(id, after, prepared, was = row.operation.status) == 1).readThisTransaction("операция")
        for (effect in settlement.effects) apply(id, effect, at)
    }

    private suspend fun apply(id: Uuid, effect: Settlement.Effect, at: Instant) {
        when (effect) {
            is Settlement.Effect.LayDown -> layDown(effect.snapshot, at, carried = sentFrom(id))
            // Коробки у нас больше нет; переход приносит пачка (PLAN D3).
            is Settlement.Effect.PackageEnded -> ended(effect.packageId, at)
            // Полку разобрали или из неё вышли: до согласия сервера ничего не трогали, и всё
            // случается здесь — одной транзакцией с закрытием операции (PLAN E6, F5).
            is Settlement.Effect.MedKitDismantled -> dismantled(effect.medKitId, effect.transferTo, at)
            is Settlement.Effect.MedKitLeft -> left(effect.medKitId, at)
            is Settlement.Effect.MedKitPublished -> publishedOnServer(effect.medKitId, at)
            is Settlement.Effect.Account -> intakes.setAccounting(id, effect.accounting)
            is Settlement.Effect.Cascade -> cascade(id, effect, at)
            is Settlement.Effect.Settled -> settled(id)
            is Settlement.Effect.Withdrawn -> withdrawn(id, effect.packageId, at)
            is Settlement.Effect.Returned -> returned(effect.packageId, effect.medKitId)
        }
    }

    /**
     * Сервер коробку забыл: у нас она местная — без версий, момента сверки и броней. Остаток —
     * подтверждённое полкой к снятию и сделанное дома после решения (PLAN E6).
     */
    private suspend fun withdrawn(id: Uuid, packageId: Uuid, at: Instant) {
        val row = packages.find(packageId) ?: return
        val words = vocabulary.snapshot()
        val pkg = row.toDomain(words)
        val confirmed = operationOf(id)?.prepared?.quantityBefore
        val carried = withdrawalOf(id)?.carried
        packages.deleteClaims(packageId)
        val after = if (confirmed != null && carried != null && confirmed.unit == pkg.quantity.unit) {
            pkg.rebased(from = carried, onto = confirmed)
        } else {
            PackageAfter.Left(pkg)
        }
        when (after) {
            is PackageAfter.Left -> {
                packages.save(after.pkg, PackageSyncState(packageId))
                // Число у коробки другое, чем с ним уносили: лечение следует за ним (PLAN D5, E6).
                following.get().follow(packageId, at)
            }
            is PackageAfter.Ended -> packages.end(after.ending, following.get(), courses, at)
        }
    }

    private suspend fun operationOf(id: Uuid): SyncOperation? =
        (queue.find(id)?.toDomain(vocabulary.snapshot()) as? StoredSyncOperation.Readable)?.operation

    private suspend fun withdrawalOf(id: Uuid): PackageSyncCommand.Withdraw? =
        operationOf(id)?.command as? PackageSyncCommand.Withdraw

    /**
     * Число, от которого команда считала, когда уходила, — если сделанное дома после неё нужно
     * перенести на ответ полки (PLAN E6). Таких команд две, и обе о границе публикации: «унёс
     * домой» помнит его сама, а создание — в замороженном запросе, потому что до ответа коробка
     * местная и человек волен из неё принимать. Остальным командам сводить нечего: их коробку
     * сервер уже знает, и его ответ и есть истина.
     */
    private suspend fun sentFrom(id: Uuid): Quantity? {
        val operation = operationOf(id) ?: return null
        return when (val command = operation.command) {
            is PackageSyncCommand.Withdraw -> command.carried
            is PackageSyncCommand.Create -> operation.prepared?.quantityBefore
            else -> null
        }
    }

    /**
     * Унести домой не вышло: коробка возвращается на полку, откуда её взяли. Той полки уже нет —
     * возвращать некуда, и коробка остаётся у человека (PLAN E6).
     */
    private suspend fun returned(packageId: Uuid, medKitId: Uuid) {
        val row = packages.find(packageId) ?: return
        val shelf = medKits.find(medKitId)?.toRef() ?: return
        val pkg = row.toDomain(vocabulary.snapshot())
        // Возврат — тоже ответ полки, а не решение человека: полка, с которой коробку брали, сама
        // помечена уборкой, и это ровно та уборка, которая не вышла (PLAN E6).
        if (pkg.medKit != shelf) packages.save(pkg.movedByAnswer(shelf), row.pack.syncState())
    }

    /**
     * Команда закрыта — и снимает ровно те пометки, которые сама поставила (PLAN E1). Их может быть
     * несколько: решение полки метит всё её содержимое одной командой. Чужих пометок закрытие не
     * касается: расход не отпускает коробку, которую решили выбросить. Кончившейся вещи нет —
     * отпускать нечего, и запрос просто не найдёт её строки.
     */
    private suspend fun settled(id: Uuid) {
        release(id)
        queue.find(id)?.operation?.medKitId?.let { releaseShelf(it) }
    }

    /**
     * Полка отпускается, когда доведено решение, ради которого она помечена, — а «доведено» у
     * решений разное. Уборка — одна команда серверу, и её ответ решение и закрывает: отказ по
     * отдельной коробке касается только её, остальные коробки живут сами за себя. Публикация —
     * полка **вместе с содержимым**, половины не бывает (D2), и потому она ждёт ещё и команды своих
     * коробок. Вместе с пометкой полки снимаются пометки тех её коробок, которые не ждут
     * собственных команд: их ставило то же решение.
     *
     * Непомеченную полку спрашивать не о чем — это обычный путь, и он ничего не стоит.
     *
     * Пометки своих коробок полка при этом не трогает: их поставила команда, и снимает их она же
     * ([release]). Полка отвечает за себя.
     */
    private suspend fun releaseShelf(medKitId: Uuid) {
        val shelf = medKits.find(medKitId) ?: return
        val kit = shelf.toDomain()
        val unclosed = when (kit.status) {
            MedKitStatus.ACTIVE -> return
            MedKitStatus.PUBLISHING -> queue.unclosedOfMedKit(medKitId)
            MedKitStatus.REMOVING -> queue.unclosedOwnOfMedKit(medKitId)
        }
        if (unclosed > 0) return
        medKits.upsert(kit.settled().toMedKitStorageEntity(shelf.syncedAt))
    }

    /**
     * Сервер завёл полку: у нас она становится общей — переходом самой аптечки, перечитанной под
     * этой транзакцией. Пометку переход не снимает: её снимет последняя закрытая команда полки
     * (PLAN E5).
     */
    private suspend fun publishedOnServer(medKitId: Uuid, at: Instant) {
        val shelf = medKits.find(medKitId) ?: return
        medKits.upsert(shelf.toDomain().published().toMedKitStorageEntity(syncedAt = at))
    }

    /**
     * Закрытая команда отпускает коробки, чью пометку поставила она сама, — и только их (PLAN E1).
     * Решение живёт, пока не отвечено оно само: старая бронь, доехавшая позже, не возвращает в
     * оборот коробку, которую полка уже решила выбросить.
     */
    private suspend fun release(operationId: Uuid) {
        val words = vocabulary.snapshot()
        for (row in packages.decidedBy(operationId)) {
            val pkg = row.toDomain(words)
            packages.save(pkg.settledBy(operationId), row.pack.syncState())
        }
    }

    /**
     * Конец коробки по ответу сервера — переходом самой пачки. Пачки уже нет — применять нечего:
     * повтор эффекта второй раз ничего не делает.
     */
    private suspend fun ended(packageId: Uuid, at: Instant) {
        val words = vocabulary.snapshot()
        val pkg = packages.find(packageId)?.toDomain(words) ?: return
        packages.end(pkg.ended(), following.get(), courses, at)
    }

    /**
     * Полку разобрали, и сервер согласился. Содержимое уходит своими доменными концами либо
     * переезжает на названную полку, и только после этого уходит строка самой аптечки: аптечки с
     * содержимым и содержимого без аптечки не бывает ни на миг (PLAN E6, F5).
     *
     * Цели уже нет — сервер переставил коробки туда, где мы их не видим: это утрата доступа, а не
     * выбрасывание, и говорить о чужой причине исчезновения мы не беремся (E3).
     */
    private suspend fun dismantled(medKitId: Uuid, transferTo: Uuid?, at: Instant) {
        val words = vocabulary.snapshot()
        val target = transferTo?.let { medKits.find(it)?.toRef() }
        for (row in packages.ofMedKit(medKitId)) {
            val pkg = row.toDomain(words)
            when {
                transferTo == null -> packages.end(pkg.ended(), following.get(), courses, at)
                // Едет коробка вместе с полкой, а не по своему решению, поэтому ждущая
                // собственного ответа переезжает наравне со всеми. Пометку снимет та команда,
                // которая её поставила: у переехавших это как раз закрываемая сейчас команда
                // полки, и снимет она их сама (PLAN E1).
                target != null -> packages.save(pkg.movedByAnswer(target), row.pack.syncState())
                else -> packages.end(pkg.ended(), following.get(), courses, at)
            }
        }
        medKits.delete(medKitId)
    }

    /**
     * Из полки вышли: коробки целы, но не у нас — каждая кончается утратой доступа, и строка полки
     * уходит следом. Курс и его история остаются (E6).
     */
    private suspend fun left(medKitId: Uuid, at: Instant) =
        medKits.loseAccess(medKitId, packages, following.get(), courses, vocabulary.snapshot(), at)

    /**
     * Разрешённый снимок поверх подтверждённого остатка и броней; разрешать здесь нечего.
     * [carried] — число, от которого команда считала, когда уходила: у «унёс домой» это то, с чем
     * уносили, у создания — то, что ушло на провод. В обоих случаях коробка до ответа была
     * местной, человек мог из неё принять, и сделанное после отправки переносится на число полки
     * (PLAN E6).
     */
    private suspend fun layDown(snapshot: PackageSnapshot, at: Instant, carried: Quantity? = null) {
        val words = vocabulary.snapshot()
        val atHome = carried?.let { packages.find(snapshot.pack.id)?.toDomain(words)?.quantity }
        packages.applySnapshot(snapshot, observedAt = at)
        if (carried != null && atHome != null) {
            val row = packages.find(snapshot.pack.id) ?: return
            val laid = row.toDomain(words)
            // Единицы разошлись — сводить нечего; коробка всё равно изменилась снимком, и лечение следует.
            if (laid.quantity.unit == carried.unit && atHome.unit == carried.unit) {
                when (val after = laid.rebased(from = carried, onto = atHome)) {
                    is PackageAfter.Left -> packages.save(after.pkg, row.pack.syncState())
                    is PackageAfter.Ended -> return packages.end(after.ending, following.get(), courses, at)
                }
            }
        }
        following.get().follow(snapshot.pack.id, at)
    }

    /**
     * Зависимость значит «нужен эффект»: не будет его у родителя — не будет и у зависимых, и у их
     * зависимых. Закрывается всё незакрытое ниже по графу — одним чтением, каждая операция раз.
     */
    private suspend fun cascade(id: Uuid, effect: Settlement.Effect.Cascade, at: Instant) {
        for (dependent in queue.dependentsOf(id)) {
            // Зависимая закрывается тем же переходом, что и своя; закрытая — не применим.
            val closed = dependent.toState().closed(effect.close, at) ?: continue
            (queue.save(dependent.id, closed, dependent.prepared, was = dependent.status) == 1).readThisTransaction("зависимая операция")
            intakes.setAccounting(dependent.id, effect.accounting)
            settled(dependent.id)
        }
    }
}

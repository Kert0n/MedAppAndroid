package com.kert0n.medapp.storage.server

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kert0n.medapp.queue.SyncCommand
import com.kert0n.medapp.queue.SyncOperation
import com.kert0n.medapp.queue.SyncOperationState
import com.kert0n.medapp.queue.RefusalReason
import com.kert0n.medapp.queue.SyncOperationStatus
import com.kert0n.medapp.queue.pack.claimChangesSince
import com.kert0n.medapp.storage.pack.PackageDao
import java.time.Instant
import kotlin.uuid.Uuid

@Dao
interface SyncOperationDao {

    /**
     * Номер выдаёт база: он монотонен и уникален, а `UNIQUE` ловит гонку двух постановок.
     * Команда своего номера не знает — он принадлежит очереди, а не тому, что предстоит
     * доставить (PLAN E2).
     *
     * [medKitId] — полка, **на которой команда действует**: там лежит коробка, когда её трогают, или
     * это сама полка. Её называет тот, кто ставит команду, — ему это известно, а команде не всегда.
     * По ней очередь держит порядок полки ([ready]).
     */
    @Transaction
    suspend fun enqueue(
        id: Uuid,
        command: SyncCommand,
        createdAt: Instant,
        groupId: Uuid? = null,
        dependsOn: Set<Uuid> = emptySet(),
        medKitId: Uuid? = SyncCommandStorageConverter.medKitIdOf(command)
    ): SyncOperation {
        val operation = SyncOperation(
            id = id,
            command = command,
            sequence = (lastSequence() ?: -1L) + 1L,
            createdAt = createdAt,
            payloadVersion = SyncCommandStorageConverter.PAYLOAD_VERSION,
            groupId = groupId,
            dependsOn = dependsOn
        )
        insert(operation.toStorageEntity(medKitId))
        insertDependencies(
            dependsOn.map { SyncOperationDependencyStorageEntity(id, it) }
        )
        return operation
    }

    @Query("SELECT MAX(sequence) FROM sync_operations")
    suspend fun lastSequence(): Long?

    @Insert
    suspend fun insert(operation: SyncOperationStorageEntity)

    @Insert
    suspend fun insertDependencies(dependencies: List<SyncOperationDependencyStorageEntity>)

    @Update
    suspend fun update(operation: SyncOperationStorageEntity)

    @Transaction
    @Query("SELECT * FROM sync_operations WHERE id = :id")
    suspend fun find(id: Uuid): SyncOperationStorageRow?

    @Transaction
    @Query("SELECT * FROM sync_operations ORDER BY sequence")
    suspend fun all(): List<SyncOperationStorageRow>

    /** Порядок по одной упаковке строится запросом, а не доменной функцией (PLAN E2). */
    @Transaction
    @Query("SELECT * FROM sync_operations WHERE package_id = :packageId ORDER BY sequence")
    suspend fun ofPackage(packageId: Uuid): List<SyncOperationStorageRow>

    /**
     * Незакрытые операции названных пачек, по возрастанию номера. Один вопрос на всю выборку:
     * список из двухсот пачек спрашивал очередь двести раз, а знание то же. Раскладывает по
     * пачкам вызывающий, он же держит длину списка в пределе переменных SQLite.
     *
     * Пачка одна — список из одной: определение «незакрытой» одно, и второго запроса под него
     * заводить незачем. Чтение, а не поток: оценка количества складывается не из них одних.
     */
    @Transaction
    @Query(
        "SELECT * FROM sync_operations WHERE package_id IN (:packageIds) " +
            "AND status NOT IN ('APPLIED', 'REFUSED', 'ACCESS_LOST') " +
            "ORDER BY sequence"
    )
    suspend fun unclosedOfPackages(packageIds: List<Uuid>): List<SyncOperationStorageRow>

    /**
     * Коробки, у которых запрос уже заморожен и не закрыт: он уходил или уйдёт тем же, и сервер
     * мог его уже применить, а ответ ещё не лёг. Полный снимок таких не кладёт: он поставил бы
     * серверное число под проекцию той же команды, и расход вычелся бы дважды. Истину по ним
     * принесёт ответ на ту же команду (PLAN E1). Неотправленная команда сюда не входит: её сервер не видел.
     */
    @Query(
        "SELECT DISTINCT package_id FROM sync_operations WHERE package_id IS NOT NULL " +
            "AND status NOT IN ('APPLIED', 'REFUSED', 'ACCESS_LOST') AND prepared_method IS NOT NULL"
    )
    suspend fun packagesInFlight(): List<Uuid>

    /**
     * Сколько у полки незакрытых **своих** команд — о ней самой, а не о её коробках: пометка полки
     * держится на них, а коробки следят за собой сами (PLAN E1).
     */
    @Query(
        "SELECT COUNT(*) FROM sync_operations WHERE med_kit_id = :medKitId AND package_id IS NULL " +
            "AND status NOT IN ('APPLIED', 'REFUSED', 'ACCESS_LOST')"
    )
    suspend fun unclosedOwnOfMedKit(medKitId: Uuid): Int

    /**
     * Сколько у полки незакрытых команд вообще — её собственных и по её коробкам. Столько ждёт
     * публикация: полка становится общей вместе с содержимым, и половины не бывает (PLAN D2, E5).
     */
    @Query(
        "SELECT COUNT(*) FROM sync_operations WHERE med_kit_id = :medKitId " +
            "AND status NOT IN ('APPLIED', 'REFUSED', 'ACCESS_LOST')"
    )
    suspend fun unclosedOfMedKit(medKitId: Uuid): Int

    /** Незакрытые команды полки строками — чтобы закрыть каждую её же переходом (PLAN G2). */
    @Transaction
    @Query(
        "SELECT * FROM sync_operations WHERE med_kit_id = :medKitId " +
            "AND status NOT IN ('APPLIED', 'REFUSED', 'ACCESS_LOST') ORDER BY sequence"
    )
    suspend fun unclosedRowsOfMedKit(medKitId: Uuid): List<SyncOperationStorageRow>

    /**
     * Что ещё касается человека: незакрытые — ждут, отправляются, ответ записан — и отказанные,
     * которым нужно его решение. Применённые, утратившие доступ и разобранные человеком экрану не
     * нужны (PLAN H3 №28). Чтение, а не поток: нечитаемые строки различает разбор, и поток строит
     * репозиторий. Состояния названы списком, а не отрицанием: по списку SQLite идёт индексом, а
     * `NOT IN` перебирал бы всю историю очереди.
     */
    @Transaction
    @Query("SELECT * FROM sync_operations WHERE status IN ('PENDING', 'SENDING', 'ANSWERED', 'REFUSED') AND dismissed_at IS NULL ORDER BY sequence")
    suspend fun outstanding(): List<SyncOperationStorageRow>

    /** Отказ разобран человеком: отметка, а не удаление — приём держится за учёт своего расхода. */
    @Query("UPDATE sync_operations SET dismissed_at = :at WHERE id = :id AND dismissed_at IS NULL")
    suspend fun dismiss(id: Uuid, at: Instant): Int

    @Transaction
    @Query("SELECT * FROM sync_operations WHERE status = :status ORDER BY sequence")
    suspend fun withStatus(status: SyncOperationStatus): List<SyncOperationStorageRow>

    /**
     * Готовые к работе: ожидающие, отправлявшиеся в момент смерти процесса и получившие ответ,
     * который ещё не применён, — у которых каждая зависимость **применена** — зависимость значит «нужен эффект», и закрытая отказом её не
     * даёт. Порядок — номер очереди; кто ещё не готов, ждёт своей зависимости.
     *
     * **Полка ждёт свои коробки, коробки ждут полку** (PLAN E3): команда о полке ждёт любую более
     * раннюю незакрытую по этой полке, а команда о коробке — более ранние по этой коробке и более
     * ранние команды самой полки. Поэтому уборка ждёт расход, поставленный на её коробку раньше, а
     * расход, поставленный позже, ждёт уборку — и ответ одной не подвешивает другую.
     *
     * Команды **двух разных коробок** одной полки друг друга не ждут: сервер их не связывает.
     * Иначе коробка на откате держала бы всю полку до своего срока, а публикация полки с пятью
     * коробками растянулась бы на пять проходов вместо одного (PLAN E1).
     */
    @Transaction
    @Query(
        "SELECT * FROM sync_operations o WHERE status IN ('PENDING', 'SENDING', 'ANSWERED') " +
            "AND (not_before IS NULL OR not_before <= :now) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM sync_operation_dependencies d JOIN sync_operations p ON p.id = d.depends_on_id " +
            "  WHERE d.operation_id = o.id AND p.status != 'APPLIED'" +
            ") AND NOT EXISTS (" +
            "  SELECT 1 FROM sync_operations e WHERE e.sequence < o.sequence " +
            "  AND e.status IN ('PENDING', 'SENDING', 'ANSWERED') " +
            "  AND (e.package_id = o.package_id " +
            "    OR (e.med_kit_id = o.med_kit_id AND (o.package_id IS NULL OR e.package_id IS NULL)))" +
            ") ORDER BY sequence"
    )
    suspend fun ready(now: Instant): List<SyncOperationStorageRow>

    /**
     * Ближайший срок среди незакрытых операций, который ещё не наступил; `null` — ждать нечего.
     * Срок повтора живёт в базе, и спрашивают о нём базу, а не память прошлого прохода.
     */
    @Query(
        "SELECT MIN(not_before) FROM sync_operations WHERE status IN ('PENDING', 'SENDING', 'ANSWERED') " +
            "AND not_before > :now"
    )
    suspend fun nextDueAt(now: Instant): Instant?

    /**
     * Ближайший срок среди **всех** незакрытых операций, прошедший в том числе: ждущая связи срока
     * не имеет и отвечает началом эпохи. `null` — незакрытых нет. В отличие от [nextDueAt], вопрос
     * здесь не «когда проснуться процессу», а «надо ли будить процесс вовсе» (PLAN E4).
     */
    @Query(
        "SELECT MIN(COALESCE(not_before, 0)) FROM sync_operations WHERE status IN ('PENDING', 'SENDING', 'ANSWERED')"
    )
    suspend fun earliestDueOfUnclosed(): Instant?

    /**
     * То же, кроме названных строк и всех, что стоят за ними: их заход пропустил, а стоящих за
     * ними — зависимых и следующих по коробке или полке, как в [ready], — не отправит ни один
     * заход, пока пропущенную не разберёт человек. Приходить за ними незачем.
     */
    @Query(
        "WITH RECURSIVE stuck(id, sequence, package_id, med_kit_id) AS (" +
            "  SELECT id, sequence, package_id, med_kit_id FROM sync_operations WHERE id IN (:except) " +
            "  UNION " +
            "  SELECT o.id, o.sequence, o.package_id, o.med_kit_id FROM sync_operations o, stuck s " +
            "  WHERE o.status IN ('PENDING', 'SENDING', 'ANSWERED') AND (" +
            "    EXISTS (SELECT 1 FROM sync_operation_dependencies d WHERE d.operation_id = o.id AND d.depends_on_id = s.id) " +
            "    OR (s.sequence < o.sequence AND (s.package_id = o.package_id " +
            "      OR (s.med_kit_id = o.med_kit_id AND (o.package_id IS NULL OR s.package_id IS NULL))))" +
            "  )" +
            ") " +
            "SELECT MIN(COALESCE(not_before, 0)) FROM sync_operations WHERE status IN ('PENDING', 'SENDING', 'ANSWERED') " +
            "AND id NOT IN (SELECT id FROM stuck)"
    )
    suspend fun earliestDueOfUnclosedExcept(except: List<Uuid>): Instant?

    /**
     * Одна дверь для состояния отправки (PLAN C1 «Переходы операции — у типа»): что писать, решил
     * переход [SyncOperationState], а здесь пишется только строка, которую прочитали той же
     * транзакцией — статус [was] ещё стоит (F5). Ноль строк — статус сменился между чтением и
     * записью, и переход не применён. Тождество строки — команда, номер, зависимости — переходом не
     * меняется и здесь не трогается; `dismissed_at` — отметка человека, не состояние ([dismiss]).
     */
    @Query(
        "UPDATE sync_operations SET status = :status, attempts = :attempts, last_error = :lastError, last_tried_at = :lastTriedAt, " +
            "prepared_method = :method, prepared_path = :path, prepared_query = :query, prepared_body = :body, " +
            "prepared_drug_version = :drugVersion, prepared_claims_version = :claimsVersion, " +
            "prepared_quantity_before = :quantityBefore, prepared_mine_before = :mineBefore, " +
            "prepared_unit_id = :unitId, prepared_at = :preparedAt, " +
            "answer_status = :answerStatus, answer_body = :answerBody, not_before = :notBefore, " +
            "outcome_unknown = :outcomeUnknown, refusal_reason = :refusalReason " +
            "WHERE id = :id AND status = :was"
    )
    suspend fun save(
        id: Uuid,
        was: SyncOperationStatus,
        status: SyncOperationStatus,
        attempts: Int,
        lastError: String?,
        lastTriedAt: Instant?,
        method: String?,
        path: String?,
        query: String?,
        body: String?,
        drugVersion: Long?,
        claimsVersion: Long?,
        quantityBefore: String?,
        mineBefore: String?,
        unitId: Uuid?,
        preparedAt: Instant?,
        answerStatus: Int?,
        answerBody: String?,
        notBefore: Instant?,
        outcomeUnknown: Boolean,
        refusalReason: RefusalReason?
    ): Int

    /** Состояние после перехода — в колонки; [prepared] — как его отдал переход (сброшенный запрос — `null`). */
    suspend fun save(id: Uuid, state: SyncOperationState, prepared: PreparedRequestStorageColumns?, was: SyncOperationStatus): Int = save(
        id = id, was = was, status = state.status, attempts = state.attempts.count, lastError = state.lastError, lastTriedAt = state.lastTriedAt,
        method = prepared?.method, path = prepared?.path, query = prepared?.query, body = prepared?.body,
        drugVersion = prepared?.drugVersion, claimsVersion = prepared?.claimsVersion,
        quantityBefore = prepared?.quantityBefore, mineBefore = prepared?.mineBefore, unitId = prepared?.unitId, preparedAt = prepared?.at,
        answerStatus = state.answer?.status, answerBody = state.answer?.body, notBefore = state.notBefore,
        outcomeUnknown = state.outcomeUnknown, refusalReason = state.refusalReason
    )

    /**
     * Все операции, которым — прямо или через другие — нужен эффект [dependsOn], каждая один раз,
     * в порядке очереди (PLAN E2, C1 «Обход зависимостей — запрос»). Граф без циклов по построению:
     * зависят только от поставленного раньше. Что с ними делать — закрыть незакрытые следом за
     * родителем, отметить разобранными закрытые каскадом, — решает вызывающий по строке; обход
     * один и ромб зависимостей в нём не удваивается.
     */
    @Query(
        "WITH RECURSIVE dependents(id) AS (" +
            "SELECT operation_id FROM sync_operation_dependencies WHERE depends_on_id = :dependsOn " +
            "UNION " +
            "SELECT d.operation_id FROM sync_operation_dependencies d JOIN dependents p ON d.depends_on_id = p.id" +
            ") SELECT o.* FROM sync_operations o JOIN dependents ON o.id = dependents.id ORDER BY o.sequence"
    )
    suspend fun dependentsOf(dependsOn: Uuid): List<SyncOperationStorageEntity>

    @Query("SELECT depends_on_id FROM sync_operation_dependencies WHERE operation_id = :id")
    suspend fun dependenciesOf(id: Uuid): List<Uuid>
}

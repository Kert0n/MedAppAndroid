package com.kert0n.medapp.storage.pack

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.kert0n.medapp.domain.course.PackageFollowing
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageAvailability
import com.kert0n.medapp.domain.pack.PackageEnding
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.queue.PackageQueueState
import com.kert0n.medapp.queue.StoredSyncOperation
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.database.chunkedForQuery
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.server.NamedThing
import com.kert0n.medapp.storage.server.SyncOperationDao
import com.kert0n.medapp.storage.server.SyncOperationStorageRow
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

@Dao
interface PackageDao {

    /** Все живые коробки со сведениями — сводке, которой нужны все сразу (PLAN H6). */
    @Transaction
    @Query("SELECT * FROM packages")
    suspend fun all(): List<PackageStorageRow>

    /** Как называются эти вещи: строке очереди нужно имя, а не вся коробка (PLAN H3 №28). */
    @Query("SELECT id, name FROM packages WHERE id IN (:ids)")
    suspend fun namesOf(ids: Collection<Uuid>): List<NamedThing>

    @Transaction
    @Query("SELECT * FROM packages WHERE id = :id")
    fun observe(id: Uuid): Flow<PackageStorageRow?>

    @Transaction
    @Query("SELECT * FROM packages WHERE med_kit_id = :medKitId ORDER BY name")
    fun observeOfMedKit(medKitId: Uuid): Flow<List<PackageStorageRow>>

    @Transaction
    @Query("SELECT * FROM packages WHERE id = :id")
    suspend fun find(id: Uuid): PackageStorageRow?

    /**
     * Пачка целиком: запись о коробке, серверная часть и личные сведения пишутся одной
     * транзакцией, потому что упаковка без любой из них — не половина пачки, а несуществующее
     * состояние (PLAN F1, F5). Запись первой: живая строка держится за неё ключом, и снимок
     * имени, единицы и формы в ней идёт за пачкой.
     */
    @Transaction
    suspend fun save(
        record: PackageRecordStorageEntity,
        pack: PackageStorageEntity,
        details: PackageDetailsStorageEntity
    ) {
        require(pack.id == details.packageId && pack.id == record.id) {
            "строки одной пачки называют один идентификатор"
        }
        upsertRecord(record)
        upsertServerPart(pack)
        upsertDetails(details)
    }

    /**
     * Разрешённый снимок целиком: серверная часть и картина броней приходят с провода вместе.
     * Применяются они **порознь** — версии у них свои и независимые, и запоздать может любая
     * половина (PLAN B3, E1). Каждая ложится, только если не старее известного.
     *
     * Картина броней без своей версии не кладётся: пустая версия значит «не читалась» (E4).
     */
    @Transaction
    suspend fun applySnapshot(
        pack: PackageStorageEntity,
        claims: ClaimsStorageEntity?,
        observedAt: Instant
    ): SnapshotApplied {
        val known = versionsOf(pack.id)
        // Местная полка серверу не принадлежит: коробка оказывается на ней, только если её унесли
        // домой, а сервер ещё не согласился. Переставить или пересчитать её снимок не может. Но у
        // сервера она пока есть, и команды, поставленные до уноса, ещё доставляются по её версиям:
        // снимок несёт ей **обе** версии — каждую, если она не старее (PLAN E3, E6). Иначе расход
        // с бронью переподготавливался бы по устаревшей версии броней без конца.
        if (liesOnLocalShelf(pack.id)) {
            if (pack.version.laysOver(known?.version)) setVersion(pack.id, pack.version)
            if (pack.claimsVersion != null && pack.claimsVersion.laysOver(known?.claimsVersion)) {
                setClaimsVersion(pack.id, pack.claimsVersion)
            }
            return SnapshotApplied(pack = false, claims = false)
        }
        val packLaysDown = pack.version.laysOver(known?.version)
        val claimsLayDown = pack.claimsVersion != null && pack.claimsVersion.laysOver(known?.claimsVersion)
        if (packLaysDown) writeServerPart(pack, observedAt)
        if (claimsLayDown) claims?.let { upsertClaims(it) }
        // Упсерт серверной части пишет строку целиком, а колонка броней принадлежит другой
        // половине: её версию — свою или прежнюю — ставит этот запрос, и только он.
        setClaimsVersion(pack.id, if (claimsLayDown) pack.claimsVersion else known?.claimsVersion)
        return SnapshotApplied(pack = packLaysDown, claims = claimsLayDown)
    }

    /**
     * Серверная часть снимка. Запись о коробке идёт за ней: недостающая — чужая пачка, увиденная
     * впервые, — заводится моментом наблюдения, а имя, единица и форма переписываются всегда;
     * личные сведения только создаются пустыми и не трогаются (PLAN E4, F1).
     */
    @Transaction
    suspend fun writeServerPart(pack: PackageStorageEntity, observedAt: Instant) {
        insertRecordIfMissing(
            PackageRecordStorageEntity(pack.id, pack.name, pack.quantityUnitId, pack.formId, observedAt)
        )
        describeRecord(pack.id, pack.name, pack.quantityUnitId, pack.formId)
        // Решение — наше, а не сведения сервера: снимок, пришедший, пока оно ждёт, его не
        // снимает. Снимает его закрытие той команды, что его поставила, — поэтому возвращается
        // пара целиком: статус без своей команды был бы невыразимым состоянием (PLAN E1).
        val decided = decisionOf(pack.id)
        upsertServerPart(pack)
        decided?.let { setDecision(pack.id, it.status, it.decidedBy) }
        insertDetailsIfMissing(PackageDetailsStorageEntity(packageId = pack.id))
    }

    @Upsert
    suspend fun upsertRecord(record: PackageRecordStorageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecordIfMissing(record: PackageRecordStorageEntity)

    /** Снимок в записи идёт за живой пачкой; момент появления остаётся прежним. */
    @Query("UPDATE package_records SET name = :name, unit_id = :unitId, form_id = :formId WHERE id = :id")
    suspend fun describeRecord(id: Uuid, name: String, unitId: Uuid, formId: Uuid?)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM packages p JOIN med_kits k ON k.id = p.med_kit_id " +
            "WHERE p.id = :id AND k.publication = 'LOCAL')"
    )
    suspend fun liesOnLocalShelf(id: Uuid): Boolean

    @Query("UPDATE packages SET version = :version WHERE id = :id")
    suspend fun setVersion(id: Uuid, version: Long?)

    /** Решение о коробке целиком: пометка и команда, которая её поставила (PLAN E1). */
    @Query("SELECT status, decided_by FROM packages WHERE id = :id")
    suspend fun decisionOf(id: Uuid): PackageDecisionStorageRow?

    @Query("UPDATE packages SET status = :status, decided_by = :decidedBy WHERE id = :id")
    suspend fun setDecision(id: Uuid, status: PackageStatus, decidedBy: Uuid?)

    /**
     * Коробки, чью пометку поставила эта команда: её закрытие снимает ровно их и никого больше
     * (PLAN E1). Решение полки метит своё содержимое, поэтому ответ по одной команде отпускает и
     * несколько коробок сразу.
     */
    @Transaction
    @Query("SELECT * FROM packages WHERE decided_by = :operationId")
    suspend fun decidedBy(operationId: Uuid): List<PackageStorageRow>

    @Query("SELECT version, claims_version FROM packages WHERE id = :id")
    suspend fun versionsOf(id: Uuid): PackageVersionsStorageRow?

    /** Версию картины броней двигает только её половина снимка — своим запросом (PLAN B3, E1). */
    @Query("UPDATE packages SET claims_version = :version WHERE id = :id")
    suspend fun setClaimsVersion(id: Uuid, version: Long?)


    /**
     * Один запрос отвечает на поиск, фильтр и сортировку сразу, и просроченные идут первыми при
     * любой сортировке: порядок нажатий на экране результат не меняет (PLAN H4).
     *
     * `HasFree` сюда не приходит — он не выражается запросом (см. `PackageQuery.Filter`), и
     * репозиторий накладывает его поверх выборки.
     *
     * Чтение, а не поток: свободное складывается ещё и из очереди с выделениями, и брать их
     * порознь нельзя. Поток строит репозиторий — из уведомлений об изменении таблиц.
     */
    @Transaction
    @Query(
        """
        SELECT p.* FROM packages p
        JOIN package_records r ON r.id = p.id
        JOIN package_details d ON d.package_id = p.id
        LEFT JOIN active_package_assignments a ON a.package_id = p.id
        WHERE (:medKitId IS NULL OR p.med_kit_id = :medKitId)
          AND (:text = '' OR p.name_search LIKE '%' || :text || '%')
          AND (
            :filter = 'NONE'
            OR (:filter = 'EXPIRED' AND d.expires_on IS NOT NULL AND d.expires_on < :today)
            OR (
              :filter = 'EXPIRING'
              AND d.expires_on IS NOT NULL
              AND d.expires_on >= :today
              AND d.expires_on <= :until
            )
            OR (:filter = 'ON_COURSE' AND a.package_id IS NOT NULL)
            OR (:filter = 'CATEGORY' AND p.category = :category)
            OR (:filter = 'FORM' AND p.form_id = :formId)
          )
        ORDER BY
          CASE WHEN d.expires_on IS NOT NULL AND d.expires_on < :today THEN 0 ELSE 1 END,
          CASE WHEN :sort = 'EXPIRY' THEN (d.expires_on IS NULL) END,
          CASE WHEN :sort = 'EXPIRY' THEN d.expires_on END,
          CASE WHEN :sort = 'ADDED_AT' THEN -r.added_at END,
          CASE WHEN :sort = 'QUANTITY' THEN p.quantity_unit_id END,
          CASE WHEN :sort = 'QUANTITY' THEN p.quantity_sort END,
          p.name_search
        """
    )
    suspend fun query(
        medKitId: Uuid?,
        text: String,
        filter: String,
        today: LocalDate,
        until: LocalDate?,
        category: String?,
        formId: Uuid?,
        sort: String
    ): List<PackageStorageRow>

    @Upsert
    suspend fun upsertServerPart(pack: PackageStorageEntity)

    @Upsert
    suspend fun upsertDetails(details: PackageDetailsStorageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDetailsIfMissing(details: PackageDetailsStorageEntity)

    @Upsert
    suspend fun upsertClaims(claims: ClaimsStorageEntity)

    /** Утрата доступа и снятие публикации не оставляют картины броней: её больше не существует. */
    @Query("DELETE FROM claims WHERE package_id = :packageId")
    suspend fun deleteClaims(packageId: Uuid)

    /**
     * Выделения активных курсов по названным пачкам. Активность видна по назначению: черновик
     * пачку не занимает, и его выделения в расчёт свободного не входят (PLAN D5, F1).
     */
    @Query(
        """
        SELECT s.package_id AS package_id, c.id AS course_id, s.allocated_doses AS allocated_doses,
               c.dose_amount AS dose_amount, c.unit_id AS unit_id
        FROM course_sources s
        JOIN active_package_assignments a
          ON a.package_id = s.package_id AND a.course_id = s.course_id
        JOIN courses c ON c.id = s.course_id
        WHERE s.package_id IN (:packageIds)
          AND c.dose_amount IS NOT NULL AND c.unit_id IS NOT NULL
        """
    )
    suspend fun allocationsOf(packageIds: List<Uuid>): List<PackageAllocationRow>

    /**
     * Живая строка уходит и уносит свои части каскадом — сведения и брони (PLAN F2). Запись о
     * коробке и всё, что за неё держится, остаются. Ноль строк значит «пачки и так нет».
     *
     * **Зовётся только из [end].** Сама по себе строка — половина конца: без следа остаток
     * пропадает без объяснения, а без доменного перехода лечение теряет источник мимо своей же
     * редакции.
     */
    @Query("DELETE FROM packages WHERE id = :id")
    suspend fun delete(id: Uuid): Int

    /**
     * Коробки, о которых сервер знает и по которым нечего ждать: без серверной версии он о коробке
     * ещё не слышал, а помеченная ждёт ответа на своё решение — её отсутствие в снимке объяснит
     * он, а не снимок (PLAN E4).
     */
    @Query(
        "SELECT p.id FROM packages p JOIN med_kits k ON k.id = p.med_kit_id " +
            "WHERE k.publication = 'PUBLISHED' AND p.version IS NOT NULL AND p.status = 'ACTIVE'"
    )
    suspend fun knownToServer(): List<Uuid>

    /**
     * То же, но на одной полке: перечитывание полки утверждает о ней целиком, и коробка, которой
     * она не назвала, у нас кончается. О чужих полках это перечитывание не говорит ничего (E4).
     */
    @Query(
        "SELECT p.id FROM packages p JOIN med_kits k ON k.id = p.med_kit_id " +
            "WHERE p.med_kit_id = :medKitId AND k.publication = 'PUBLISHED' " +
            "AND p.version IS NOT NULL AND p.status = 'ACTIVE'"
    )
    suspend fun knownToServerOn(medKitId: Uuid): List<Uuid>

    /** Все живые коробки — чтобы снимок не вернул убранную, пока он летел (PLAN C0, E4). */
    @Query("SELECT id FROM packages")
    suspend fun held(): List<Uuid>

    @Transaction
    @Query("SELECT * FROM packages WHERE med_kit_id = :medKitId ORDER BY name")
    suspend fun ofMedKit(medKitId: Uuid): List<PackageStorageRow>

    /** Живые коробки по названным номерам — тому, кто собирает расклад по пачкам курса (PLAN D5). */
    @Transaction
    @Query("SELECT * FROM packages WHERE id IN (:ids)")
    suspend fun among(ids: List<Uuid>): List<PackageStorageRow>
}

/**
 * Проекции пачек одним чтением на порцию: оценка количества — незакрытые команды поверх
 * подтверждённого остатка по возрастанию номера (команда, которую нечем прочитать после
 * обновления приложения, в число не входит — PLAN E1, F4); выделение и держащий курс — из
 * назначения активному курсу; последний мой приём — по приёмам из коробки (PLAN D4). Спрашивать
 * очередь, выделения и приёмы про каждую пачку значило бы двести запросов там, где хватает
 * одного; порядок по `sequence` внутри пачки группировка сохраняет.
 *
 * Зовётся внутри уже открытой транзакции того, кто читает: списка пачек и обеспечения курса.
 */
suspend fun PackageDao.projectionsOf(
    packages: List<Package>,
    queue: SyncOperationDao,
    intakes: IntakeDao,
    words: Vocabulary
): List<PackageProjection> {
    val ids = packages.map { it.id }
    val allocations = ids.chunkedForQuery().flatMap { allocationsOf(it) }.associateBy { it.packageId }
    val unclosed = ids.chunkedForQuery()
        .flatMap { queue.unclosedOfPackages(it) }
        .groupBy { requireNotNull(it.operation.packageId) { "операция пачки называет свою пачку" } }
    val lastUsed = ids.chunkedForQuery().flatMap { intakes.lastTakenFrom(it) }.associate { it.packageId to it.lastUsedAt }
    return packages.map { pkg ->
        val state = PackageQueueState(pkg, commandsOf(unclosed[pkg.id].orEmpty(), words))
        val allocation = allocations[pkg.id]
        val availability = PackageAvailability(
            pkg = pkg,
            effective = state.amount,
            myAllocation = allocation?.allocated(words, pkg.quantity.unit) ?: Quantity.zero(pkg.quantity.unit)
        )
        pkg.projection(
            availability = availability,
            hasUnconfirmedChanges = state.hasUnconfirmedChanges,
            holdingCourseId = allocation?.courseId,
            lastUsedAt = lastUsed[pkg.id]
        )
    }
}

/** Команды пачки из строк очереди; нечитаемую после обновления приложения пропускаем (PLAN F4). */
private fun commandsOf(rows: List<SyncOperationStorageRow>, words: Vocabulary): List<PackageSyncCommand> =
    rows.mapNotNull {
        (it.toDomain(words) as? StoredSyncOperation.Readable)?.operation?.command as? PackageSyncCommand
    }

/**
 * Снимок пачки, разрешённый в домен, — в базу. Единственная дверь: половины расходятся только
 * тут, и только по своим версиям, поэтому версия картины броней всегда описывает ту картину,
 * что лежит рядом (PLAN B3, E1). Серверное число ложится только через неё — полным снимком,
 * чтением перед отправкой или ответом на команду; чужое изменение просто становится нашим
 * числом, истории у коробки нет (D7).
 *
 * Зовётся внутри уже открытой транзакции того, кто снимок кладёт.
 */
suspend fun PackageDao.applySnapshot(snapshot: PackageSnapshot, observedAt: Instant): SnapshotApplied =
    applySnapshot(
        snapshot.pack.toStorageEntity(snapshot.sync),
        snapshot.pack.claims?.toStorageEntity(snapshot.pack.id),
        observedAt
    )

/**
 * Пачка целиком: запись о коробке, живая строка и личные сведения собираются из одной сущности.
 * Порознь их не бывает, и раскладывать пачку на три строки каждому вызывающему незачем (PLAN F1).
 */
suspend fun PackageDao.save(pkg: Package, sync: PackageSyncState = PackageSyncState(pkg.id)) =
    save(pkg.record.toStorageEntity(), pkg.toStorageEntity(sync), pkg.toDetailsStorageEntity())

/**
 * Дверь конца коробки — одна на все причины (PLAN D3, D5): сначала лечение теряет источник у
 * своего владельца ([PackageFollowing.lost] — переход, событие сокращения, брони), потом
 * снимается назначение (целостность: FK не даст удалить занятую строку), потом уходит строка.
 * Запись о коробке и приёмы, которые за неё держатся, остаются (D6).
 */
suspend fun PackageDao.end(ending: PackageEnding, following: PackageFollowing, courses: CourseDao, at: Instant) {
    following.lost(ending.pkg.ref, at)
    courses.releasePackage(ending.pkg.id)
    delete(ending.record.id)
}

/**
 * Ложится ли пришедшая версия поверх известной: запоздалый снимок свежий не перекрывает
 * (PLAN E1). Неизвестная версия — с любой стороны — не возражает: откатывать нечего.
 */
private fun Long?.laysOver(known: Long?): Boolean = this == null || known == null || this >= known

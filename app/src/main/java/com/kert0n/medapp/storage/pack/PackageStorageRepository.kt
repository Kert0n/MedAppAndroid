package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageEnding
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageStatus
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSyncState
import com.kert0n.medapp.storage.course.CourseReallocation
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/**
 * Хранение упаковок. Собирает пачку из трёх её таблиц — серверной части, личных сведений и
 * картины броней (PLAN F1, H1). Потоки несут проекции — величины для экрана, собранные одним
 * чтением в одной транзакции: пачка вместе с доступностью — оценкой количества, чужими бронями
 * и занятым активным курсом (PLAN D4). Сущность отдаёт [find], и действительна она в
 * транзакции сценария, который её читал.
 *
 * Оценку количества считает очередь: репозиторий берёт незакрытые команды по номеру и сворачивает
 * их существующим `PackageQueueState`, а домену отдаёт готовое число (PLAN E1).
 */
interface PackageStorageRepository {

    fun observe(id: Uuid): Flow<PackageProjection?>

    suspend fun find(id: Uuid): Package?

    /** Список экрана: `today` приходит аргументом, потому что база системных часов не читает. */
    fun list(query: PackageQuery, today: LocalDate): Flow<List<PackageProjection>>

    /**
     * Заведение пачки: своей — без обвязки синхронизации, чужой — вместе со снимком сервера.
     * Правка существующей идёт своими операциями: у них есть предусловия, а у общей записи их
     * нет, и она молча обнуляла бы то, чего действие человека не касается.
     */
    suspend fun add(pkg: Package, sync: PackageSyncState = PackageSyncState(pkg.id))

    /**
     * Правка описательных сведений. Остаток, обвязка синхронизации и картина броней не
     * трогаются: экран, загрузивший пачку когда-то раньше, переименованием их не переписывает.
     *
     * `false` — пачки больше нет.
     */
    suspend fun describe(packageId: Uuid, facts: PackageFacts): Boolean

    /**
     * Коробки больше нет — **единственная дверь** к этому, и звать её можно только с [PackageEnding],
     * который построил переход пачки. Одной транзакцией ложится всё, чего порознь не бывает
     * (PLAN D3, F5):
     *
     * - источник, снятый **доменным переходом** у каждого лечения, которое коробку держало, — и у
     *   начатого, и у черновика, — с ростом редакции;
     * - живая строка со своими частями: сведениями и бронями.
     *
     * Запись о коробке и приёмы, которые за неё держатся, остаются (D6). `false` — пачки и так нет.
     */
    suspend fun end(ending: PackageEnding, at: Instant): Boolean

    /**
     * Решение по коробке принято, а полка ещё не ответила: коробка получает пометку [status]
     * своим переходом (PLAN E1). Снимает пометку не сценарий, а закрытие команды в очереди, поэтому
     * `ACTIVE` сюда не передают. `false` — пачки больше нет.
     */
    suspend fun mark(packageId: Uuid, status: PackageStatus): Boolean

    /**
     * Живые пачки аптечки — сущности для сценария, который разбирает её по коробкам в своей
     * транзакции: переставляет или выбрасывает каждую доменным переходом (PLAN E6).
     */
    suspend fun contentsOf(medKitId: Uuid): List<Package>

    /**
     * Снимок переписывает серверную часть целиком и не касается личных сведений (PLAN E4).
     * Половины применяются порознь, каждая по своей версии: запоздалая свежую не откатывает, и
     * версия картины броней никогда не расходится с самой картиной (PLAN B3, E1).
     */
    suspend fun applySnapshot(snapshot: PackageSnapshot, observedAt: Instant): SnapshotApplied

    /**
     * Обвязка синхронизации пачки для экрана состояния синхронизации (PLAN H3 №28): версии и
     * момент последней сверки. Своим методом, а не полем проекции — они принадлежат доставке, а
     * не пачке, и остальным экранам не нужны. `null` — пачки больше нет.
     */
    fun observeSyncState(id: Uuid): Flow<PackageSyncState?>

    /** `null` снимает картину броней: аптечка не опубликована либо доступ утрачен. */
    suspend fun saveClaims(packageId: Uuid, claims: Claims?)

    /**
     * Пересчёт, утилизация и перенос: новое состояние пачки ложится одной транзакцией вместе с
     * пересчитанными выделениями [reallocation] (PLAN F5). Команду серверу, если она нужна, ставит
     * служба очереди в той же транзакции — репозиторий про очередь не знает.
     *
     * Переход применяется к нынешнему состоянию пачки, прочитанному в той же транзакции: утилизация
     * не списывает в минус от давно изменившегося числа. Обвязка синхронизации при этом не
     * трогается: версии и время сверки принадлежат снимку сервера, а не действию человека (PLAN E4).
     *
     * Кончившаяся коробка (пересчёт в ноль, утилизация всего) строки не оставляет; запись о ней
     * остаётся (PLAN D3). `false` — пачки больше нет: писать переход некуда.
     */
    suspend fun adjust(
        adjustment: PackageAdjustment,
        reallocation: CourseReallocation? = null,
        at: Instant
    ): Boolean
}

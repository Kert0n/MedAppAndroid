package com.kert0n.medapp.feature.medkits

import androidx.annotation.CheckResult
import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Хранение аптечек. Отдаёт домен, а не строки: выше по стеку о Room не знают, а сохранённые
 * сведения приходят потоком — «обновить экран после записи» руками не нужно нигде (PLAN H1).
 */
interface MedKitRecords : MedKitReadings {

    suspend fun find(id: Uuid): MedKit?

    /** Новая полка: заводится местной, обвязки синхронизации у неё ещё нет (PLAN D2). */
    suspend fun add(medKit: MedKit)

    /**
     * Название и место хранения — названными полями к полке, прочитанной в той же транзакции:
     * публикацию, число участников, пометку и обвязку синхронизации правка не трогает (PLAN D2,
     * F5). `false` — полки больше нет.
     */
    @CheckResult
    suspend fun describe(medKitId: Uuid, name: String, location: String?): Boolean

    /**
     * Строка аптечки уходит. Содержимое к этому моменту уже переехало или удалено — что с ним
     * делать, решает сценарий, а не хранение (PLAN E6, F5). `false` — аптечки и так нет.
     */
    @CheckResult
    suspend fun delete(id: Uuid): Boolean

    /**
     * Решение по полке принято, а сервер ещё не ответил: полка получает пометку [status] своим
     * переходом (PLAN E1, E6). Снимает её закрытие команды в очереди, поэтому `ACTIVE` сюда не
     * передают. `false` — аптечки больше нет.
     */
    @CheckResult
    suspend fun mark(medKitId: Uuid, status: MedKitStatus): Boolean

    /** Снимок трогает только число участников: остального сервер о нашей аптечке не знает. */
    suspend fun applyServerParticipants(id: Uuid, participantCount: Long, syncedAt: Instant)

    /** Полки, которые стоят у сервера: их знает учётка, и без неё они уходят (PLAN G2). */
    suspend fun published(): List<Uuid>

    /**
     * Полки у нас больше нет — утратой доступа, той же дверью, что «полки не стало в снимке»:
     * коробки кончаются своим концом со следом, курсы теряют источники, брони не трогаются —
     * снимать их некому. Незакрытые поручения полки закрывает очередь до этого.
     */
    suspend fun loseAccess(medKitId: Uuid, at: Instant)
}

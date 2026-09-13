package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.medkit.MedKitStatus
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.toDomain
import com.kert0n.medapp.network.value.VocabularyResolver
import java.time.Instant
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Разрешает всё, что снимок пачки называет, — единицу, форму, аптечку — и собирает его в домен
 * одним исходом (PLAN E3, E4). Незнакомое бывает двух родов, и ответы на них разные:
 *
 * - **словарь** ещё не дочитан — единица или форма появятся, стоит прочитать словарь: ждать с
 *   названной причиной, не закрывая операцию;
 * - **полка** незнакома — коробка ушла туда, где нас нет: сосед переставил её в свою полку. Ждать
 *   нечего, это окончательный ответ — [Resolution.Elsewhere], и для нас это утрата доступа (E6).
 *   Исключение одно: полку, которую тот же полный снимок и приносит (`arriving`), он заводит сам,
 *   и коробке на ней есть куда лечь (E4).
 *
 * Снимок, нарушающий инварианты домена, — данные сервера, а не ошибка программиста, и из прохода
 * исключением не выходит.
 *
 * Хранение получает только [Resolution.Resolved] и ничего не разрешает само.
 */
class PackageSnapshotResolver @Inject constructor(
    private val vocabulary: VocabularyResolver,
    private val storage: QueueStorage
) {

    /**
     * [words] — заход разбора, общий для многих снимков: словарь в нём дочитывается не больше
     * раза, сколько бы коробок ни промахнулось.
     */
    suspend fun resolve(
        snapshot: PackageSnapshotNetworkDTO,
        at: Instant,
        arriving: Set<Uuid> = emptySet(),
        words: VocabularyResolver.Session = vocabulary.session()
    ): Resolution {
        val medKitId = snapshot.pack.medKitId
        val medKit = storage.medKit(medKitId)
            ?: MedKitRef(medKitId, MedKit.Publication.PUBLISHED, MedKitStatus.ACTIVE).takeIf { medKitId in arriving }
            ?: return Resolution.Elsewhere(medKitId)
        val resolution = try {
            words.resolve { snapshot.toDomain(it, medKit, addedAt = at, observedAt = at) }
        } catch (invalid: IllegalArgumentException) {
            return Resolution.Unresolved("снимок вне контракта: ${invalid.message}", stop = false)
        }
        return when (resolution) {
            is VocabularyResolver.Resolution.Resolved -> Resolution.Resolved(resolution.value)
            is VocabularyResolver.Resolution.Unresolved ->
                Resolution.Unresolved(resolution.reason, stop = resolution.failure != null)
        }
    }

    /** Чем кончилось: снимок в домене — или что именно неизвестно и надо ли останавливать проход. */
    sealed interface Resolution {

        data class Resolved(val snapshot: PackageSnapshot) : Resolution

        /** Коробка на полке [medKitId], которой у нас нет: туда, где нас нет. Окончательно. */
        data class Elsewhere(val medKitId: Uuid) : Resolution

        /** [stop] — словарь не дочитался из-за связи: дальше в этом проходе идти незачем. */
        data class Unresolved(val reason: String, val stop: Boolean) : Resolution
    }
}

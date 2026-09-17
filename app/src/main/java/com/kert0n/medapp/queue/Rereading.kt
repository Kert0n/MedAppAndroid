package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.network.account.asUnavailability
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Человек открыл вещь — вещь перечитывается (PLAN E4, решение владельца 2026-09-17). Заход
 * (`Synchronization`) отвечает на «что вообще изменилось» и спрашивает всё сразу; а тот, кто
 * смотрит на одну полку или одну коробку, неправильное число увидит **здесь**, и ради него не
 * нужно спрашивать про весь остальной дом: полка отвечает своим `GET /v1/med-kits/{id}`, коробка —
 * своим `GET /v1/drugs/{id}`.
 *
 * Ложится ответ тем же путём, что снимок: разбор `PackageSnapshotResolver`, укладка
 * `SnapshotStorage.lay` — по версиям, не трогая коробку с запросом в полёте и не возвращая
 * убранное (E1, C0). Следование курса за коробкой достаётся ему оттуда же.
 *
 * **Утверждает оно только о том, о чём спрашивало.** Полка называет своё содержимое целиком, и
 * коробка, которой в ней не оказалось, у нас кончается; про чужие полки и их коробки перечитывание
 * не говорит ничего. Сказать «всего остального у нас больше нет» вправе один полный снимок (C0).
 *
 * Очередь не трогается вовсе: неотправленное уезжает своим порядком, и чужой ответ его не
 * отменяет.
 */
@Singleton
class Rereading @Inject constructor(
    private val api: MedAppApi,
    private val storage: SnapshotStorage,
    private val snapshots: PackageSnapshotResolver,
    private val vocabulary: VocabularyResolver,
    private val clock: Clock
) {

    /** Полка и всё, что на ней лежит. 404 — полки у нас больше нет: её убрали или нас вывели. */
    suspend fun medKit(medKitId: Uuid): Outcome {
        val at = clock.instant()
        // Спрашивается до сети, как и у снимка: что человек сделает, пока ответ летит, ответ не знает.
        val knew = storage.serverKnows()
        val ours = storage.packagesKnownOn(medKitId)
        val read = when (val answer = api.medKit(medKitId)) {
            is ApiResult.Success -> answer.value
            is ApiResult.Failure -> return when (answer.failure) {
                ApiFailure.NotFound -> {
                    storage.lay(gone(medKits = setOf(medKitId), packages = emptySet(), knew = knew), at)
                    Outcome.Gone
                }
                else -> Outcome.Refused(answer.failure.asUnavailability())
            }
        }
        val words = vocabulary.session()
        val resolved = ArrayList<PackageSnapshot>()
        for (dto in read.packages) {
            val resolution = snapshots.resolve(dto, at, words = words)
            if (resolution is PackageSnapshotResolver.Resolution.Resolved) resolved += resolution.snapshot
        }
        storage.lay(
            ServerSnapshot(
                participants = mapOf(read.id to read.participantCount),
                packages = resolved,
                goneMedKits = emptySet(),
                // Полка назвала своё содержимое целиком: чего в нём нет, того на ней больше нет.
                // Коробка, которую не удалось разрешить, сервером названа и не пропала.
                gonePackages = ours - read.packages.mapTo(HashSet()) { it.pack.id },
                arrivedMedKits = emptySet(),
                heldPackages = knew.heldPackages
            ),
            at
        )
        return Outcome.Read
    }

    /** Одна коробка. 404 — её больше нет: выбросили, кончилась или унесли туда, где нас нет. */
    suspend fun pack(packageId: Uuid): Outcome {
        val at = clock.instant()
        val knew = storage.serverKnows()
        val read = when (val answer = api.packageSnapshot(packageId)) {
            is ApiResult.Success -> answer.value
            is ApiResult.Failure -> return when (answer.failure) {
                ApiFailure.NotFound -> {
                    storage.lay(gone(medKits = emptySet(), packages = setOf(packageId), knew = knew), at)
                    Outcome.Gone
                }
                else -> Outcome.Refused(answer.failure.asUnavailability())
            }
        }
        val resolution = snapshots.resolve(read, at)
        // Коробка на полке, которой у нас нет, — это не «пропала»: её переставили туда, где нас
        // нет, и отвечает за это полный снимок со своим утверждением о целом (E6).
        val snapshot = (resolution as? PackageSnapshotResolver.Resolution.Resolved)?.snapshot
            ?: return Outcome.Read
        storage.lay(
            ServerSnapshot(
                participants = emptyMap(),
                packages = listOf(snapshot),
                goneMedKits = emptySet(),
                gonePackages = emptySet(),
                arrivedMedKits = emptySet(),
                heldPackages = knew.heldPackages
            ),
            at
        )
        return Outcome.Read
    }

    private fun gone(medKits: Set<Uuid>, packages: Set<Uuid>, knew: ServerKnowledge) = ServerSnapshot(
        participants = emptyMap(),
        packages = emptyList(),
        goneMedKits = medKits,
        gonePackages = packages,
        arrivedMedKits = emptySet(),
        heldPackages = knew.heldPackages
    )

    /**
     * Чем кончилось. Прочитали — свежее уже в базе, и экран увидит его сам; вещи больше нет —
     * тоже записано; отказ — ничего не изменилось, и человеку о нём не говорят: он о перечитывании
     * не просил, а что не доехало, скажет экран состояния синхронизации (PLAN H3 №28).
     */
    sealed interface Outcome {

        data object Read : Outcome

        data object Gone : Outcome

        data class Refused(val reason: Unavailability) : Outcome
    }
}

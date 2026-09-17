package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.network.account.asUnavailability
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Человек открыл вещь, по которой решает, — вещь перечитывается (PLAN E4, решения владельца
 * 2026-09-17). Заход (`Synchronization`) отвечает на «что вообще изменилось» и спрашивает всё
 * сразу; а тот, кто смотрит на одну коробку, неправильное число увидит **здесь**, и ради него не
 * нужно спрашивать про весь дом: коробка отвечает своим `GET /v1/drugs/{id}`.
 *
 * Содержимое полки перечитывание не трогает — его обновляет заход. Со списка полок читается только
 * **сам список** (`GET /v1/med-kits`): в каких полках мы ещё есть и сколько в них людей, — чтобы
 * человек не решал ничего о полке, из которой его вывели.
 *
 * Ложится ответ тем же путём, что снимок: разбор `PackageSnapshotResolver`, укладка
 * `SnapshotStorage.lay` — по версиям, не трогая коробку с запросом в полёте и не возвращая
 * убранное (E1, C0). Очередь не трогается: неотправленное уезжает своим порядком и ложится поверх
 * прочитанного.
 */
@Singleton
class Rereading @Inject constructor(
    private val api: MedAppApi,
    private val storage: SnapshotStorage,
    private val snapshots: PackageSnapshotResolver,
    private val clock: Clock
) {

    /**
     * Список полок. Полка, которую сервер знал, а список не назвал, у нас кончается вместе с
     * содержимым: её убрали у всех или нас вывели. Полку, которой у нас нет, список не приносит —
     * без содержимого она была бы половиной полки; её приносит заход.
     */
    suspend fun medKits(): Outcome {
        val at = clock.instant()
        // Спрашивается до сети, как и у снимка: что человек сделает, пока ответ летит, ответ не знает.
        val knew = storage.serverKnows()
        val read = when (val answer = api.medKits()) {
            is ApiResult.Success -> answer.value
            is ApiResult.Failure -> return Outcome.Refused(answer.failure.asUnavailability())
        }
        val named = read.mapTo(HashSet()) { it.id }
        storage.lay(
            ServerSnapshot(
                participants = read.filter { it.id in knew.heldMedKits }.associate { it.id to it.participantCount },
                packages = emptyList(),
                goneMedKits = knew.medKits - named,
                gonePackages = emptySet(),
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
                    storage.lay(
                        ServerSnapshot(
                            participants = emptyMap(),
                            packages = emptyList(),
                            goneMedKits = emptySet(),
                            gonePackages = setOf(packageId),
                            arrivedMedKits = emptySet(),
                            heldPackages = knew.heldPackages
                        ),
                        at
                    )
                    Outcome.Gone
                }
                else -> Outcome.Refused(answer.failure.asUnavailability())
            }
        }
        val snapshot = when (val resolution = snapshots.resolve(read, at)) {
            is PackageSnapshotResolver.Resolution.Resolved -> resolution.snapshot
            // Коробка на полке, которой у нас нет, — это не «пропала»: её переставили туда, где нас
            // нет, и отвечает за это полный снимок со своим утверждением о целом (E6).
            is PackageSnapshotResolver.Resolution.Elsewhere -> return Outcome.Read
            // Ничего не легло: словарь не дочитался или ответ вне контракта. Прочитанным это не
            // считается — иначе коробка сошла бы за свежую, а человек видел бы старое число.
            is PackageSnapshotResolver.Resolution.Unresolved -> return Outcome.Refused(
                if (resolution.stop) Unavailability.NO_CONNECTION else Unavailability.SERVER_SILENT
            )
        }
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

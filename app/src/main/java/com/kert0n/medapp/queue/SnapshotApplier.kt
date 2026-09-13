package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.network.account.asUnavailability
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.pack.PackageSnapshot
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Читает у сервера полное состояние и кладёт его к себе (PLAN E4). Дешёвой сверки не существует —
 * состав полки и число участников чужой расход не двигают (B6), — поэтому спрашивается всё сразу:
 * `GET /v1/users/me` отвечает не списком изменений, а **утверждением о целом**: вот полки, которые
 * я вижу, и вот всё, что на них лежит. Поэтому полка, которой у нас нет, в нём — штатный случай:
 * нас позвали, а ответ на вступление потерялся, — и заводится она здесь же.
 *
 * Сеть между транзакциями, а не внутри (F5): сначала читается ответ, потом он разрешается в домен,
 * и только разрешённое ложится одной записью. Промах словаря дочитывается один раз за чтение —
 * единица или форма, появившаяся на сервере, это обычное дело, а не ошибка; строка, которой не
 * помог и свежий словарь, пропускается с названной причиной, а не роняет всё чтение.
 *
 * **Живёт в очереди, а не в сети.** Разрешить снимок нельзя, не спросив, что за полку он называет,
 * а это вопрос к хранилищу: `PackageSnapshotResolver` потому и лежит здесь. Сеть про очередь не
 * знает (H1), так что место у чтения снимка — рядом с остальной доставкой, а не в `network/`.
 */
@Singleton
class SnapshotApplier @Inject constructor(
    private val api: MedAppApi,
    private val storage: SnapshotStorage,
    private val vocabulary: VocabularyResolver,
    private val snapshots: PackageSnapshotResolver,
    private val clock: Clock
) {

    suspend fun refresh(): Outcome {
        val at = clock.instant()
        // Спрашивается до сети: что человек заведёт или уберёт, пока снимок летит, ответ не знает.
        val knew = storage.serverKnows()
        val read = when (val answer = api.snapshot()) {
            is ApiResult.Success -> answer.value
            is ApiResult.Failure -> return Outcome.Refused(answer.failure.asUnavailability())
        }
        val participants = read.medKits.associate { it.id to it.participantCount }
        // Полку, которой у нас не было, снимок заводит: сервер назвал её нашей — вступили, а ответ
        // на вступление потерялся. Была и пропала, пока снимок летел, — её убрали, не заводим.
        val arriving = participants.keys - knew.heldMedKits
        val resolved = ArrayList<PackageSnapshot>()
        val skipped = ArrayList<String>()
        var vocabularyRefreshable = true
        for (medKit in read.medKits) {
            for (dto in medKit.packages) {
                var resolution = snapshots.resolve(dto, at, arriving)
                if (resolution is PackageSnapshotResolver.Resolution.Unresolved && !resolution.stop && vocabularyRefreshable) {
                    vocabularyRefreshable = false
                    if (vocabulary.refresh() is ApiResult.Success) resolution = snapshots.resolve(dto, at, arriving)
                }
                when (resolution) {
                    is PackageSnapshotResolver.Resolution.Resolved -> resolved += resolution.snapshot
                    // Полки уже нет: её убрали у нас, пока снимок летел, и класть коробку некуда.
                    is PackageSnapshotResolver.Resolution.Elsewhere ->
                        skipped += "коробка ${dto.pack.id} на убранной полке ${resolution.medKitId}"
                    is PackageSnapshotResolver.Resolution.Unresolved ->
                        skipped += "коробка ${dto.pack.id}: ${resolution.reason}"
                }
            }
        }
        // Чего в снимке нет, к тому доступа больше нет. Считается это по названным номерам, а не
        // по разрешённым: коробка, которую не удалось разрешить, названа сервером и не пропала.
        val named = read.medKits.flatMapTo(HashSet()) { medKit -> medKit.packages.map { it.pack.id } }
        val snapshot = ServerSnapshot(
            participants = participants,
            packages = resolved,
            goneMedKits = knew.medKits - participants.keys,
            gonePackages = knew.packages - named,
            arrivedMedKits = arriving,
            heldPackages = knew.heldPackages
        )
        storage.lay(snapshot, at)
        return Outcome.Applied(medKits = participants.size, packages = resolved.size, skipped = skipped)
    }

    /**
     * Чем кончилось чтение. Различает поведение экрана состояния синхронизации: прочитали — видно
     * время последнего успешного обновления, и [skipped] говорит, что легло не всё; не прочитали —
     * названа причина, а кэш остаётся прежним (PLAN E4, H3 №28).
     */
    sealed interface Outcome {

        data class Applied(val medKits: Int, val packages: Int, val skipped: List<String>) : Outcome

        data class Refused(val reason: Unavailability) : Outcome
    }
}

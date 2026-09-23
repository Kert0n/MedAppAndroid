package com.kert0n.medapp.queue

import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.network.account.asUnavailability
import com.kert0n.medapp.network.medkit.MedKitNetworkDTO
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.pack.PackageSnapshot
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Читает у сервера, что нам доступно, и кладёт это к себе (PLAN E4). Дешёвой сверки не существует —
 * состав полки и число участников чужой расход не двигают (B6), — поэтому спрашивается всё сразу:
 * `GET /v1/users/me` отвечает не списком изменений, а **утверждением о целом**: вот полки, которые
 * я вижу, и вот всё, что на них лежит. Поэтому полка, которой у нас нет, в нём — штатный случай:
 * нас позвали, а ответ на вступление потерялся, — и заводится она здесь же.
 *
 * Вступление — тот же снимок, только одной полки: сервер отвечает на него полкой с содержимым, и
 * разрешается и ложится она тем же путём ([join]). Утверждения о целом в нём нет, и пропажи оно не
 * объявляет.
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
        val resolution = resolve(read.medKits, arriving, at)
        // Чего в снимке нет, к тому доступа больше нет. Считается это по названным номерам, а не
        // по разрешённым: коробка, которую не удалось разрешить, названа сервером и не пропала.
        val named = read.medKits.flatMapTo(HashSet()) { medKit -> medKit.packages.map { it.pack.id } }
        val snapshot = ServerSnapshot(
            participants = participants,
            packages = resolution.packages,
            goneMedKits = knew.medKits - participants.keys,
            gonePackages = knew.packages - named,
            arrivedMedKits = arriving,
            heldPackages = knew.heldPackages
        )
        storage.lay(snapshot, at)
        return Outcome.Applied(medKits = participants.size, packages = resolution.packages.size, skipped = resolution.skipped)
    }

    /**
     * Вступление по ключу приглашения: сервер отвечает полкой с содержимым, и она ложится одной
     * записью — полка «Общей аптечкой» (C0), коробки со своими записями. Что делать, когда ответа
     * нет или сервер говорит «уже вступили», решает сценарий: механизм отвечает только тем, что
     * услышал.
     */
    suspend fun join(key: InvitationKey): Joining {
        val at = clock.instant()
        val knew = storage.serverKnows()
        val joined = when (val answer = api.joinMedKit(MembershipPostNetworkDTO(key.value))) {
            is ApiResult.Success -> answer.value
            is ApiResult.Failure -> return when (val failure = answer.failure) {
                // Неизвестный, истёкший ключ и вышедший пригласивший неразличимы (B6).
                ApiFailure.NotFound -> Joining.InvitationInvalid
                ApiFailure.Conflict -> Joining.AlreadyMember
                ApiFailure.OutcomeUnknown -> Joining.OutcomeUnknown
                else -> Joining.Refused(failure.asUnavailability())
            }
        }
        val arriving = setOf(joined.id) - knew.heldMedKits
        val resolution = resolve(listOf(joined), arriving, at)
        val snapshot = ServerSnapshot(
            participants = mapOf(joined.id to joined.participantCount),
            packages = resolution.packages,
            goneMedKits = emptySet(),
            gonePackages = emptySet(),
            arrivedMedKits = arriving,
            heldPackages = knew.heldPackages
        )
        storage.lay(snapshot, at)
        return Joining.Joined(joined.id)
    }

    /** Коробки названных полок — в домен; [arriving] — полки, которые этот же ответ и заводит. */
    private suspend fun resolve(medKits: List<MedKitNetworkDTO>, arriving: Set<Uuid>, at: Instant): Resolution {
        val resolved = ArrayList<PackageSnapshot>()
        val skipped = ArrayList<String>()
        // Один заход разбора на весь ответ: сколько бы коробок ни назвали незнакомую единицу,
        // словарь дочитывается один раз.
        val words = vocabulary.session()
        for (medKit in medKits) {
            for (dto in medKit.packages) {
                when (val resolution = snapshots.resolve(dto, at, arriving, words)) {
                    is PackageSnapshotResolver.Resolution.Resolved -> resolved += resolution.snapshot
                    // Полки уже нет: её убрали у нас, пока снимок летел, и класть коробку некуда.
                    is PackageSnapshotResolver.Resolution.Elsewhere ->
                        skipped += "коробка ${dto.pack.id} на убранной полке ${resolution.medKitId}"
                    is PackageSnapshotResolver.Resolution.Unresolved ->
                        skipped += "коробка ${dto.pack.id}: ${resolution.reason}"
                }
            }
        }
        return Resolution(resolved, skipped)
    }

    private class Resolution(val packages: List<PackageSnapshot>, val skipped: List<String>)

    /**
     * Чем кончилось чтение. Различает поведение экрана состояния синхронизации: прочитали — видно
     * время последнего успешного обновления, и [Applied.skipped] говорит, что легло не всё; не
     * прочитали — названа причина, а кэш остаётся прежним (PLAN E4, H3 №28).
     */
    sealed interface Outcome {

        data class Applied(val medKits: Int, val packages: Int, val skipped: List<String>) : Outcome

        data class Refused(val reason: Unavailability) : Outcome
    }

    /** Что сервер ответил на вступление — ровно те случаи, которые сценарий разбирает по-разному. */
    sealed interface Joining {

        /** Вступили: полка [medKitId] с содержимым уже лежит у нас. */
        data class Joined(val medKitId: Uuid) : Joining

        /** 409: мы уже в этой полке — возможно, прошлый ответ потерялся. Номера полки сервер не даёт. */
        data object AlreadyMember : Joining

        /** Ключ неизвестен, истёк или пригласивший вышел: для нас это одно (B6). */
        data object InvitationInvalid : Joining

        /** Запрос уходил, а ответа нет: вступление могло состояться. */
        data object OutcomeUnknown : Joining

        /** Сервера сейчас нет — связи, ответа или нашей учётки. */
        data class Refused(val reason: Unavailability) : Joining
    }
}

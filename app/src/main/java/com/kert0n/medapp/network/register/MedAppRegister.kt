package com.kert0n.medapp.network.register

import com.kert0n.medapp.domain.medkit.InvitationKey
import com.kert0n.medapp.network.account.asUnavailability
import com.kert0n.medapp.network.medkit.MedKitNetworkDTO
import com.kert0n.medapp.network.medkit.MembershipPostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.server.MedAppApi
import com.kert0n.medapp.queue.Register
import com.kert0n.medapp.queue.pack.PackageSnapshot
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Реестр MedApp: `GET /v1/users/me`, `GET /v1/med-kits`, `GET /v1/drugs/{id}` и вступление.
 * Коробки собираются в домен одним заходом словаря на ответ: сколько бы их ни назвали
 * незнакомую единицу, словарь дочитывается один раз.
 */
class MedAppRegister @Inject constructor(
    private val api: MedAppApi,
    private val snapshots: PackageSnapshotResolver,
    private val clock: Clock
) : Register {

    override suspend fun whole(arriving: (named: Set<Uuid>) -> Set<Uuid>): Register.Whole =
        when (val answer = api.snapshot()) {
            is ApiResult.Success -> {
                val participants = answer.value.medKits.associate { it.id to it.participantCount }
                Register.Whole.Answered(read(answer.value.medKits, participants, arriving(participants.keys)))
            }
            is ApiResult.Failure -> Register.Whole.Refused(answer.failure.asUnavailability())
        }

    override suspend fun shelves(): Register.Shelves = when (val answer = api.medKits()) {
        is ApiResult.Success -> Register.Shelves.Answered(answer.value.associate { it.id to it.participantCount })
        is ApiResult.Failure -> Register.Shelves.Refused(answer.failure.asUnavailability())
    }

    override suspend fun pack(packageId: Uuid): Register.Pack = when (val answer = api.packageSnapshot(packageId)) {
        is ApiResult.Success -> when (val resolution = snapshots.resolve(answer.value, clock.instant())) {
            is PackageSnapshotResolver.Resolution.Resolved -> Register.Pack.Answered(resolution.snapshot)
            is PackageSnapshotResolver.Resolution.Elsewhere -> Register.Pack.Elsewhere
            is PackageSnapshotResolver.Resolution.Unresolved -> Register.Pack.Unresolved(resolution.stop)
        }
        is ApiResult.Failure ->
            if (answer.failure == ApiFailure.NotFound) Register.Pack.Gone
            else Register.Pack.Refused(answer.failure.asUnavailability())
    }

    override suspend fun join(key: InvitationKey, arriving: (medKitId: Uuid) -> Set<Uuid>): Register.Joined =
        when (val answer = api.joinMedKit(MembershipPostNetworkDTO(key.value))) {
            is ApiResult.Success -> {
                val joined = answer.value
                Register.Joined.Answered(joined.id, read(listOf(joined), mapOf(joined.id to joined.participantCount), arriving(joined.id)))
            }
            is ApiResult.Failure -> when (val failure = answer.failure) {
                // Неизвестный, истёкший ключ и вышедший пригласивший неразличимы (B6).
                ApiFailure.NotFound -> Register.Joined.InvitationInvalid
                ApiFailure.Conflict -> Register.Joined.AlreadyMember
                ApiFailure.OutcomeUnknown -> Register.Joined.OutcomeUnknown
                else -> Register.Joined.Refused(failure.asUnavailability())
            }
        }

    /** Коробки названных полок — в домен; [arriving] — полки, которые этот же ответ и заводит. */
    private suspend fun read(medKits: List<MedKitNetworkDTO>, participants: Map<Uuid, Long>, arriving: Set<Uuid>): Register.Read {
        val at = clock.instant()
        val resolved = ArrayList<PackageSnapshot>()
        val skipped = ArrayList<String>()
        val words = snapshots.session()
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
        // Названные сервером коробки считаются по номерам, а не по собранным: несобранная не пропала.
        val named = medKits.flatMapTo(HashSet()) { medKit -> medKit.packages.map { it.pack.id } }
        return Register.Read(participants, resolved, named, arriving, skipped)
    }
}

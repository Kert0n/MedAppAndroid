package com.kert0n.medapp.network.delivery

import com.kert0n.medapp.network.pack.PackageSnapshotNetworkDTO
import com.kert0n.medapp.network.pack.PackageSnapshotResolver
import com.kert0n.medapp.network.server.ApiFailure
import com.kert0n.medapp.network.server.ApiResult
import com.kert0n.medapp.network.value.VocabularyResolver
import com.kert0n.medapp.queue.Answer
import com.kert0n.medapp.queue.Courier
import com.kert0n.medapp.queue.DeliveryStatus
import com.kert0n.medapp.queue.Expected
import com.kert0n.medapp.queue.Fetched
import com.kert0n.medapp.queue.PreparedRequest
import com.kert0n.medapp.queue.Receipt
import java.time.Clock
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Курьер MedApp: везёт поручения через [CourierDoor] и переводит провод на язык поручения — код
 * ответа в [DeliveryStatus], тело квитанции в [Answer], снимок в домен. Механический повтор той
 * же посылки уже сделан под дверью; решать, что со статусом делать дальше, курьер не берётся.
 */
class MedAppCourier @Inject constructor(
    private val door: CourierDoor,
    private val vocabulary: VocabularyResolver,
    private val snapshots: PackageSnapshotResolver,
    private val clock: Clock
) : Courier {

    override fun pass(): Courier.Pass = Pass(vocabulary.session())

    private inner class Pass(private val words: VocabularyResolver.Session) : Courier.Pass {

        override suspend fun send(request: PreparedRequest): DeliveryStatus = when (val result = door.send(request)) {
            is ApiResult.Success -> DeliveryStatus.Received(Receipt(result.value.status, result.value.body))
            is ApiResult.Failure -> result.failure.asStatus()
        }

        override suspend fun read(receipt: Receipt, expects: Expected): Answer = when (val parsed = expects.read(receipt)) {
            is ApiResult.Failure -> Answer.Garbled((parsed.failure as ApiFailure.Protocol).reason)
            is ApiResult.Success -> when (val read = parsed.value) {
                is ParsedReceipt.Snapshot -> when (val resolution = resolve(read.snapshot)) {
                    is PackageSnapshotResolver.Resolution.Resolved -> Answer.Snapshot(resolution.snapshot)
                    is PackageSnapshotResolver.Resolution.Elsewhere -> Answer.Elsewhere
                    is PackageSnapshotResolver.Resolution.Unresolved -> Answer.Unresolved(resolution.reason, resolution.stop)
                }
                ParsedReceipt.Gone -> Answer.Gone
                is ParsedReceipt.Claim -> Answer.Claim
                ParsedReceipt.Nothing -> Answer.Nothing
            }
        }

        override suspend fun packageSnapshot(packageId: Uuid): Fetched = when (val read = door.packageSnapshot(packageId)) {
            is ApiResult.Success -> when (val resolution = resolve(read.value)) {
                is PackageSnapshotResolver.Resolution.Resolved -> Fetched.Snapshot(resolution.snapshot)
                is PackageSnapshotResolver.Resolution.Elsewhere -> Fetched.Elsewhere
                is PackageSnapshotResolver.Resolution.Unresolved -> Fetched.Unresolved(resolution.reason, resolution.stop)
            }
            is ApiResult.Failure -> Fetched.Declined(read.failure.asStatus())
        }

        override suspend fun medKitIsOurs(medKitId: Uuid): Boolean? = when (val ours = door.medKitIsOurs(medKitId)) {
            is ApiResult.Success -> ours.value
            is ApiResult.Failure -> null
        }

        override suspend fun refreshVocabularyOnce(): Boolean = words.refreshOnce()

        override val vocabularyRefreshFailed: Boolean get() = words.refreshFailed

        private suspend fun resolve(snapshot: PackageSnapshotNetworkDTO): PackageSnapshotResolver.Resolution =
            snapshots.resolve(snapshot, clock.instant(), words = words)
    }
}

/** Код отказа — словами поручения. */
internal fun ApiFailure.asStatus(): DeliveryStatus = when (this) {
    is ApiFailure.Invalid -> DeliveryStatus.Invalid
    ApiFailure.Unauthorized, ApiFailure.RegistrationRefused -> DeliveryStatus.NoPass
    ApiFailure.NotFound -> DeliveryStatus.Absent
    ApiFailure.Conflict -> DeliveryStatus.Taken
    ApiFailure.PreconditionFailed -> DeliveryStatus.Outdated
    ApiFailure.PreconditionRequired -> DeliveryStatus.VersionMissing
    is ApiFailure.TooManyRequests -> DeliveryStatus.Throttled(retryAfter)
    ApiFailure.Unavailable -> DeliveryStatus.Unreachable
    ApiFailure.OutcomeUnknown -> DeliveryStatus.OutcomeUnknown
    is ApiFailure.Protocol -> DeliveryStatus.Garbled(reason)
}

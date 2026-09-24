package com.kert0n.medapp.network.delivery

import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.network.medkit.MedKitPostNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPatchNetworkDTO
import com.kert0n.medapp.network.pack.ClaimPostNetworkDTO
import com.kert0n.medapp.network.pack.PackagePatchNetworkDTO
import com.kert0n.medapp.network.pack.PackagePostNetworkDTO
import com.kert0n.medapp.network.pack.PackageSyncNetworkDTO
import com.kert0n.medapp.network.pack.toPatchNetworkDTO
import com.kert0n.medapp.network.server.MedAppRoutes
import com.kert0n.medapp.network.server.medAppJson
import com.kert0n.medapp.network.server.toNetworkDTO
import com.kert0n.medapp.network.value.toNetworkAmount
import com.kert0n.medapp.queue.PreparedRequest
import com.kert0n.medapp.queue.ResourceVersion
import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Команда по пачке как запрос — с теми предусловиями, что у пачки в этот момент: версией
 * состояния, версией броней, подтверждённым остатком и своей бронью. Собранный запрос не
 * пересобирается: повтор с неизвестным исходом идёт тем же, а устаревший сбрасывается и
 * собирается заново по свежему состоянию (PLAN E2, E3).
 *
 * Расход — всегда `sync` под [operationId]: у него есть номер, и повтор под ним сервер применит
 * один раз; внеплановый расход — тот же `sync` без блока брони (решение владельца, B4).
 * Пересчёт везёт [confirmed] с разницей человека; итог ноль — `DELETE`: это форма провода, а не
 * смысл команды. Правка сведений везёт свои поля поверх [known] — того, что у сервера сейчас, — а
 * создание везёт [confirmed] и [known] как есть: у сервера этой коробки нет, и поверх нечего
 * класть (PLAN E6).
 */
fun PackageSyncCommand.toPreparedRequest(
    operationId: Uuid,
    sync: PackageSyncState,
    confirmed: Quantity?,
    mine: Quantity?,
    at: Instant,
    known: PackageSharedFacts? = null
): PreparedRequest {
    val version = sync.version
    val claimsVersion = sync.claimsVersion
    return when (this) {
        is PackageSyncCommand.Create -> {
            val amount = requireNotNull(confirmed) { "создание готовится по прочитанному остатку" }
            val facts = requireNotNull(known) { "создание готовится по прочитанным сведениям" }
            prepared(
                method = "POST",
                path = MedAppRoutes.packagesOf(medKitId),
                body = medAppJson.encodeToString(
                    PackagePostNetworkDTO.serializer(),
                    PackagePostNetworkDTO(
                        id = packageId,
                        name = facts.name,
                        amount = amount.toNetworkAmount(),
                        unitId = amount.unit.id,
                        formId = facts.form?.id,
                        category = facts.category,
                        manufacturer = facts.manufacturer,
                        country = facts.country,
                        description = facts.description
                    )
                ),
                sync = sync, confirmed = confirmed, mine = mine, at = at
            )
        }
        is PackageSyncCommand.Describe -> {
            val theirs = requireNotNull(known) { "правка сведений готовится по прочитанным сведениям" }
            prepared(
                method = "PATCH",
                path = MedAppRoutes.pack(packageId),
                body = medAppJson.encodeToString(
                    PackagePatchNetworkDTO.serializer(),
                    requireNotNull(onto(theirs)) { "несводимую правку отвергает подготовка" }.toPatchNetworkDTO(theirs, version)
                ),
                sync = sync, confirmed = confirmed, mine = mine, at = at
            )
        }
        is PackageSyncCommand.CorrectStock -> {
            val target = onto(requireNotNull(confirmed) { "пересчёт готовится по прочитанному остатку" })
            if (target.isZero) prepared(
                method = "DELETE",
                path = MedAppRoutes.pack(packageId),
                query = versionQuery(version),
                sync = sync, confirmed = confirmed, mine = mine, at = at
            ) else prepared(
                method = "PATCH",
                path = MedAppRoutes.pack(packageId),
                body = medAppJson.encodeToString(
                    PackagePatchNetworkDTO.serializer(),
                    PackagePatchNetworkDTO(amount = target.toNetworkAmount(), version = version?.toNetworkDTO())
                ),
                sync = sync, confirmed = confirmed, mine = mine, at = at
            )
        }
        is PackageSyncCommand.Move -> prepared(
            method = "PUT",
            path = MedAppRoutes.packageIn(targetMedKitId, packageId),
            query = versionQuery(version),
            sync = sync, confirmed = confirmed, mine = mine, at = at
        )
        // Унести домой и выбросить на проводе одно и то же: сервер снимает коробку по версии.
        is PackageSyncCommand.Delete, is PackageSyncCommand.Withdraw -> prepared(
            method = "DELETE",
            path = MedAppRoutes.pack(packageId),
            query = versionQuery(version),
            sync = sync, confirmed = confirmed, mine = mine, at = at
        )
        is PackageSyncCommand.Consume -> prepared(
            method = "PUT",
            path = MedAppRoutes.sync(packageId, operationId),
            body = medAppJson.encodeToString(
                PackageSyncNetworkDTO.serializer(),
                PackageSyncNetworkDTO(
                    consumed = amount.quantity.toNetworkAmount(),
                    packageVersion = version?.toNetworkDTO(),
                    // Внеплановый расход и нулевая бронь — без блока брони: первому бронь не
                    // нужна, у второго снятие уезжает зависимым `ReleaseClaim` (PLAN E2).
                    claim = claimAfter?.takeUnless { it.isZero }?.let {
                        PackageSyncNetworkDTO.Claim(it.toNetworkAmount(), claimsVersion?.toNetworkDTO())
                    }
                )
            ),
            sync = sync, confirmed = confirmed, mine = mine, at = at
        )
        is PackageSyncCommand.SetClaim ->
            // Бронь на сервере одна на пару «человек и пачка»: есть своя — правится, нет — заявляется.
            if (mine == null) prepared(
                method = "POST",
                path = MedAppRoutes.CLAIMS,
                body = medAppJson.encodeToString(
                    ClaimPostNetworkDTO.serializer(),
                    ClaimPostNetworkDTO(packageId, amount.toNetworkAmount(), claimsVersion?.toNetworkDTO())
                ),
                sync = sync, confirmed = confirmed, mine = mine, at = at
            ) else prepared(
                method = "PATCH",
                path = MedAppRoutes.claim(packageId),
                body = medAppJson.encodeToString(
                    ClaimPatchNetworkDTO.serializer(),
                    ClaimPatchNetworkDTO(amount.toNetworkAmount(), claimsVersion?.toNetworkDTO())
                ),
                sync = sync, confirmed = confirmed, mine = mine, at = at
            )
        is PackageSyncCommand.ReleaseClaim -> prepared(
            method = "DELETE",
            path = MedAppRoutes.claim(packageId),
            query = versionQuery(claimsVersion),
            sync = sync, confirmed = confirmed, mine = mine, at = at
        )
    }
}

private fun prepared(
    method: String,
    path: String,
    query: Map<String, String> = emptyMap(),
    body: String? = null,
    sync: PackageSyncState,
    confirmed: Quantity?,
    mine: Quantity?,
    at: Instant
) = PreparedRequest(
    method = method,
    path = path,
    query = query,
    body = body,
    drugVersion = sync.version,
    claimsVersion = sync.claimsVersion,
    quantityBefore = confirmed,
    mineBefore = mine,
    preparedAt = at
)

private fun versionQuery(version: ResourceVersion?): Map<String, String> =
    version?.let { mapOf("version" to it.number.toString()) } ?: emptyMap()

/**
 * Команда по аптечке как запрос. Предусловий у аптечки нет (PLAN B3), поэтому замораживать здесь
 * нечего, кроме самого пути: удаление и выход повторяются тем же запросом, а «уже нет» закрывает
 * их как исполненные.
 */
fun MedKitSyncCommand.toPreparedRequest(at: Instant): PreparedRequest = when (this) {
    is MedKitSyncCommand.Publish -> PreparedRequest(
        method = "POST",
        path = MedAppRoutes.MED_KITS,
        body = medAppJson.encodeToString(MedKitPostNetworkDTO.serializer(), MedKitPostNetworkDTO(medKitId)),
        preparedAt = at
    )
    is MedKitSyncCommand.Delete -> PreparedRequest(
        method = "DELETE",
        path = MedAppRoutes.medKit(medKitId),
        query = transferTo?.let { mapOf("targetMedKitId" to it.toString()) } ?: emptyMap(),
        preparedAt = at
    )
    is MedKitSyncCommand.Leave -> PreparedRequest(
        method = "DELETE",
        path = MedAppRoutes.membership(medKitId),
        preparedAt = at
    )
}

package com.kert0n.medapp.network.pack

import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.pack.Claims
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageFacts
import com.kert0n.medapp.domain.pack.PackageSharedFacts
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.domain.value.Vocabulary
import com.kert0n.medapp.network.value.formOrMiss
import com.kert0n.medapp.network.value.unitOrMiss
import com.kert0n.medapp.queue.pack.PackageSnapshot
import com.kert0n.medapp.queue.pack.PackageSyncState
import java.math.BigDecimal
import java.time.Instant

/**
 * Провод → домен. Единица и форма приходят идентификаторами и разрешаются по снимку словаря;
 * промах — `VocabularyMiss`, и решает его резолвер, а не этот маппер. Аптечку приносит вызывающий:
 * снимок называет её номером, а ссылка есть у того, кто читает базу. Личных сведений в снимке
 * нет по контракту: [addedAt] — момент первого наблюдения чужой пачки, свою вызывающий заводит
 * сам. Пачка на сервере жива по определению — нулевой остаток сервер уничтожает.
 */
fun PackageSnapshotNetworkDTO.toDomain(
    vocabulary: Vocabulary,
    medKit: MedKitRef,
    addedAt: Instant,
    observedAt: Instant
): PackageSnapshot = PackageSnapshot(
    pack = Package(
        id = pack.id,
        medKit = medKit.also { require(it.id == pack.medKitId) { "снимок пачки называет другую аптечку" } },
        facts = PackageFacts(
            shared = PackageSharedFacts(
                name = pack.name,
                form = pack.formId?.let(vocabulary::formOrMiss),
                category = pack.category,
                manufacturer = pack.manufacturer,
                country = pack.country,
                description = pack.description
            )
        ),
        quantity = Quantity(BigDecimal(pack.amount), vocabulary.unitOrMiss(pack.unitId)),
        addedAt = addedAt,
        claims = Claims(total = BigDecimal(claims.total), mine = claims.mine?.let(::BigDecimal))
    ),
    sync = PackageSyncState(
        packageId = pack.id,
        version = pack.version.toVersion(),
        claimsVersion = claims.version.toVersion(),
        syncedAt = observedAt
    )
)

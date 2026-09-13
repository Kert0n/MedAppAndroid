package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitStatus
import java.time.Instant
import kotlin.uuid.Uuid

val HOME_KIT: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000011")
val SHARED_KIT: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000012")

/**
 * Аптечка как вещь, а не как идентификатор: домен принимает её саму, и подставить вместо неё
 * чужой `Uuid` тогда нечем.
 */
fun medKit(
    id: Uuid = HOME_KIT,
    name: String = "Домашняя",
    location: String? = null,
    publication: MedKit.Publication = MedKit.Publication.LOCAL,
    participantCount: Long = 1,
    createdAt: Instant = Instant.EPOCH,
    status: MedKitStatus = MedKitStatus.ACTIVE
) = MedKit(
    id = id,
    name = name,
    location = location,
    publication = publication,
    participantCount = participantCount,
    createdAt = createdAt,
    status = status
)

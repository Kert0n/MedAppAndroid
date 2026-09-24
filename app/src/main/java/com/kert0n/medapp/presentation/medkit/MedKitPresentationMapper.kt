package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKitProjection
import java.time.Instant

/**
 * Строит состояние экрана из доменной проекции: как та собрана, представление не знает (PLAN H1).
 *
 * [syncedAt] приходит аргументом: момент последней сверки принадлежит обвязке синхронизации,
 * а не аптечке.
 */
fun MedKitProjection.toPresentationDTO(syncedAt: Instant? = null): MedKitPresentationDTO =
    MedKitPresentationDTO(
        id = id,
        name = name,
        location = location,
        publication = publication,
        participantCount = participantCount,
        createdAt = createdAt,
        syncedAt = syncedAt,
        isShared = isShared,
        isPublished = isPublished,
        acceptsInvitations = acceptsInvitations,
        contents = contents,
        status = status
    )

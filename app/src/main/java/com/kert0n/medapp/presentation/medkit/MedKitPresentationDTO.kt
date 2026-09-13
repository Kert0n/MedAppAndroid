package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import java.time.Instant
import kotlin.uuid.Uuid

/** Состояние аптечки сравнивается по всем полям, а не только по её тождеству. */
data class MedKitPresentationDTO(
    val id: Uuid,
    val name: String,
    val location: String?,
    val publication: MedKit.Publication,
    val participantCount: Long,
    val createdAt: Instant,
    val syncedAt: Instant?,
    val isShared: Boolean,
    val acceptsInvitations: Boolean,
    val contents: MedKitContents
)

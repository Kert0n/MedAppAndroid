package com.kert0n.medapp.domain.medkit

import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Аптечка глазами экрана — величина с равенством по содержимому, без переходов (PLAN H1).
 * Строит её сама аптечка ([MedKit.projection]); сущность остаётся в транзакции.
 */
data class MedKitProjection(
    val id: Uuid,
    val name: String,
    val location: String?,
    val publication: MedKit.Publication,
    val participantCount: Long,
    val createdAt: Instant,
    val isShared: Boolean,
    val acceptsInvitations: Boolean,
    val status: MedKitStatus = MedKitStatus.ACTIVE
)

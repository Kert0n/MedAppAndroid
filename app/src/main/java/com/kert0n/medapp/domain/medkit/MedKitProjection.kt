package com.kert0n.medapp.domain.medkit

import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Аптечка глазами экрана — величина с равенством по содержимому, без переходов (PLAN H1).
 * Строит её сама аптечка ([MedKit.projection]); сущность остаётся в транзакции. [contents] —
 * что на ней лежит: считает тот, кто читал коробки, и приносит полке (PLAN D2).
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
    val contents: MedKitContents,
    val status: MedKitStatus = MedKitStatus.ACTIVE
)

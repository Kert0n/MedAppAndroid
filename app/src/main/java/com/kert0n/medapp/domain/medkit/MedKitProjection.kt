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
) {

    /** Полка на сервере — общая по публикации, сколько бы в ней ни было людей (PLAN D2). */
    val isPublished: Boolean get() = publication == MedKit.Publication.PUBLISHED

    /** Полка своя, и решения сделать её общей ещё нет: о публикации можно спросить (PLAN E5). */
    val publishable: Boolean get() = publication == MedKit.Publication.LOCAL && status != MedKitStatus.PUBLISHING
}

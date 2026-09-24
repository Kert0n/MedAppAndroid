package com.kert0n.medapp.presentation.medkit

import com.kert0n.medapp.domain.medkit.MedKit
import com.kert0n.medapp.domain.medkit.MedKitContents
import com.kert0n.medapp.domain.medkit.MedKitStatus
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Состояние аптечки сравнивается по всем полям, а не только по её тождеству.
 *
 * Умолчаний у полей нет: забытое поле должно не собраться, а не тихо приехать пустым. Проекция
 * полки этим до сих пор грешит — там `status` с умолчанием при единственном строителе.
 */
data class MedKitPresentationDTO(
    val id: Uuid,
    val name: String,
    val location: String?,
    val publication: MedKit.Publication,
    val participantCount: Long,
    val createdAt: Instant,
    val syncedAt: Instant?,
    val isShared: Boolean,
    /** Полка на сервере — ответ самой полки; участники тут ни при чём. */
    val isPublished: Boolean,
    val acceptsInvitations: Boolean,
    val contents: MedKitContents,
    /** Решение о полке, которое ещё едет серверу: его видно там же, где полку (PLAN E5, E6). */
    val status: MedKitStatus
)

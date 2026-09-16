package com.kert0n.medapp.presentation.operation

import com.kert0n.medapp.storage.server.OutstandingOperation
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Незакрытая операция глазами экрана (PLAN H3 №28). Своя форма, а не читаемая из хранения: экран
 * видит только домен и представление (`LayerBoundariesTest`), и чужой тип к нему не едет ни
 * импортом, ни полным именем — тем же правилом, по которому у коробки есть своё DTO.
 *
 * [subject] — имя вещи, о которой шла речь; `null` у строки, которую нечем прочитать: имя лежит
 * внутри неё. [recountable] — коробка, чьё расхождение по числу лечится пересчётом (REQ-045).
 */
data class OutstandingOperationPresentationDTO(
    val id: Uuid,
    val trouble: Trouble,
    val about: About,
    val subject: String?,
    val reason: Reason?,
    val retryAt: Instant?,
    val recountable: Uuid?
) {

    /** Ждёт, отвергнута, нечитаема — три вида строк, и человек в каждом делает разное. */
    enum class Trouble { WAITING, REFUSED, UNREADABLE }

    enum class About { PACKAGE_CREATED, PACKAGE_CHANGED, PACKAGE_MOVED, PACKAGE_REMOVED, INTAKE, CLAIM, MED_KIT, UNKNOWN }

    enum class Reason { INVALID, NOT_ENOUGH, UNIT_CHANGED, STALE, CONFLICT, SUPERSEDED, UNREADABLE }
}

fun OutstandingOperation.toPresentationDTO(): OutstandingOperationPresentationDTO =
    OutstandingOperationPresentationDTO(
        id = id,
        trouble = when (trouble) {
            OutstandingOperation.Trouble.WAITING -> OutstandingOperationPresentationDTO.Trouble.WAITING
            OutstandingOperation.Trouble.REFUSED -> OutstandingOperationPresentationDTO.Trouble.REFUSED
            OutstandingOperation.Trouble.UNREADABLE -> OutstandingOperationPresentationDTO.Trouble.UNREADABLE
        },
        about = when (about) {
            OutstandingOperation.About.PACKAGE_CREATED -> OutstandingOperationPresentationDTO.About.PACKAGE_CREATED
            OutstandingOperation.About.PACKAGE_CHANGED -> OutstandingOperationPresentationDTO.About.PACKAGE_CHANGED
            OutstandingOperation.About.PACKAGE_MOVED -> OutstandingOperationPresentationDTO.About.PACKAGE_MOVED
            OutstandingOperation.About.PACKAGE_REMOVED -> OutstandingOperationPresentationDTO.About.PACKAGE_REMOVED
            OutstandingOperation.About.INTAKE -> OutstandingOperationPresentationDTO.About.INTAKE
            OutstandingOperation.About.CLAIM -> OutstandingOperationPresentationDTO.About.CLAIM
            OutstandingOperation.About.MED_KIT -> OutstandingOperationPresentationDTO.About.MED_KIT
            OutstandingOperation.About.UNKNOWN -> OutstandingOperationPresentationDTO.About.UNKNOWN
        },
        subject = subject,
        reason = reason?.let {
            when (it) {
                OutstandingOperation.Reason.INVALID -> OutstandingOperationPresentationDTO.Reason.INVALID
                OutstandingOperation.Reason.NOT_ENOUGH -> OutstandingOperationPresentationDTO.Reason.NOT_ENOUGH
                OutstandingOperation.Reason.UNIT_CHANGED -> OutstandingOperationPresentationDTO.Reason.UNIT_CHANGED
                OutstandingOperation.Reason.STALE -> OutstandingOperationPresentationDTO.Reason.STALE
                OutstandingOperation.Reason.CONFLICT -> OutstandingOperationPresentationDTO.Reason.CONFLICT
                OutstandingOperation.Reason.SUPERSEDED -> OutstandingOperationPresentationDTO.Reason.SUPERSEDED
                OutstandingOperation.Reason.UNREADABLE -> OutstandingOperationPresentationDTO.Reason.UNREADABLE
            }
        },
        retryAt = retryAt,
        recountable = recountable
    )

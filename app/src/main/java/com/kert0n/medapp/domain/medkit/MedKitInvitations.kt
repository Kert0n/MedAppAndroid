package com.kert0n.medapp.domain.medkit

import com.kert0n.medapp.domain.Unavailability

/**
 * Выдача ключа приглашения в полку. Приглашение существует только на сервере — хранить у себя
 * нечего, и в очередь его не поставить (PLAN C3), — поэтому действие выполняет сеть, а домен
 * называет его и его исходы (H1).
 *
 * Можно ли звать в эту полку, решает не порт, а полка сама (`MedKit.acceptsInvitations`): порт
 * только спрашивает сервер.
 */
interface MedKitInvitations {

    suspend fun issue(medKit: MedKit): Issue

    /** Что ответил сервер — ровно те случаи, которые сценарий разбирает по-разному. */
    sealed interface Issue {

        data class Issued(val key: InvitationKey) : Issue

        /** Полка нам больше не доступна: её убрали у всех или нас из неё вывели. */
        data object NotAccessible : Issue

        data class Unavailable(val reason: Unavailability) : Issue
    }
}

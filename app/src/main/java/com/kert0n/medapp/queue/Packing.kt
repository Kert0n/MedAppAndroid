package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.medkit.MedKitSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncCommand
import com.kert0n.medapp.queue.pack.PackageSyncState
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Упаковка поручения в посылку — по решению очереди и в тот же миг, когда оно принято:
 * посылка замораживается вместе с предусловиями и на повторе не пересобирается, иначе
 * неустановленный расход ушёл бы со свежим предусловием и списался бы дважды (PLAN E2, E3).
 * Как посылка выглядит на проводе, знает исполнитель — сеть; очередь её носит, не читая.
 */
interface Packing {

    /** Поручение по пачке с предусловиями [sync] и тем, с чем решено его слать, — [send]. */
    fun pack(operationId: Uuid, command: PackageSyncCommand, sync: PackageSyncState, send: Preparation.Send, at: Instant): PreparedRequest

    /** Поручение по аптечке: предусловий у неё нет, замораживается сам адрес (PLAN B3). */
    fun pack(command: MedKitSyncCommand, at: Instant): PreparedRequest
}

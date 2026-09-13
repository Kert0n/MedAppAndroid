package com.kert0n.medapp.queue.medkit

import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.SHARED_KIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Команда очереди по аптечке: опубликовать, удалить для всех, выйти. */
class MedKitSyncCommandTest {

    @Test
    fun everyCommandNamesItsMedKit() {
        val commands: List<MedKitSyncCommand> = listOf(
            MedKitSyncCommand.Publish(HOME_KIT),
            MedKitSyncCommand.Delete(HOME_KIT),
            MedKitSyncCommand.Leave(HOME_KIT)
        )
        assertEquals(3, commands.size)
        assertEquals(listOf(HOME_KIT), commands.map { it.medKitId }.distinct())
    }

    @Test
    fun contentsAreNotTransferredIntoTheKitBeingDeleted() {
        assertThrows(IllegalArgumentException::class.java) {
            MedKitSyncCommand.Delete(HOME_KIT, transferTo = HOME_KIT)
        }
        assertEquals(SHARED_KIT, MedKitSyncCommand.Delete(HOME_KIT, SHARED_KIT).transferTo)
    }

    @Test
    fun deletionWithoutATargetTakesTheContentsWithIt() {
        // `null` — это «удалить вместе с содержимым», а не «переносить куда-то» (PLAN E6).
        assertNull(MedKitSyncCommand.Delete(HOME_KIT).transferTo)
    }

    @Test
    fun leavingIsNotDeleting() {
        // Аптечка цела, изменился наш доступ; курс и его история остаются (PLAN D5, E6).
        assertNotEquals(MedKitSyncCommand.Leave(HOME_KIT), MedKitSyncCommand.Delete(HOME_KIT))
    }
}

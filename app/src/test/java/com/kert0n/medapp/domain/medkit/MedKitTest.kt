package com.kert0n.medapp.domain.medkit

import com.kert0n.medapp.fixture.HOME_KIT
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedKitTest {

    /**
     * Экран предлагает сделать полку общей по проекции, а решает сама полка: ответы совпадают при
     * любой публикации и любой пометке — полке, о которой уже принимается решение (убирают,
     * публикуют), публикацию не предлагают (замечание разбора #89).
     */
    @Test
    fun theProjectionOffersPublicationExactlyWhenTheShelfAcceptsIt() {
        for (publication in MedKit.Publication.entries) for (status in MedKitStatus.entries) {
            val shelf = com.kert0n.medapp.fixture.medKit(publication = publication, status = status)
            assertEquals(
                "$publication / $status",
                shelf.refusesPublication() == null,
                shelf.projection(MedKitContents(packages = 0, expired = 0)).publishable
            )
        }
    }


    @Test
    fun describingKeepsPublicationAndClearsLocation() {
        val original = kit(publication = MedKit.Publication.PUBLISHED, participants = 3)
        val edited = original.describe(name = "Дачная", location = null)
        assertEquals("Дачная", edited.name)
        assertNull(edited.location)
        assertEquals(original.id, edited.id)
        assertEquals(original.publication, edited.publication)
        assertEquals(original.participantCount, edited.participantCount)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals("Домашняя", original.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun describingRejectsBlankName() {
        kit().describe(" ", null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun describingRejectsBlankLocation() {
        kit().describe("Домашняя", " ")
    }

    @Test
    fun localKitDoesNotHandOutInvitations() {
        // На сервере её нет — приглашать некуда.
        assertFalse(kit(publication = MedKit.Publication.LOCAL).acceptsInvitations)
    }

    /**
     * Ответ сервера делает полку общей и больше ничего не трогает. Приглашений она при этом ещё не
     * выдаёт: содержимое едет своими командами, и половины полки приглашённому не показывают
     * (PLAN D2, E5).
     */
    @Test
    fun theServerAnswerMakesTheShelfSharedAndKeepsEverythingElse() {
        val local = kit(publication = MedKit.Publication.LOCAL).markPublishing()
        val published = local.published()
        assertEquals(MedKit.Publication.PUBLISHED, published.publication)
        assertTrue(published.answersToServer)
        assertFalse(published.acceptsInvitations)
        assertTrue(published.settled().acceptsInvitations)
        assertEquals(local.id, published.id)
        assertEquals(local.name, published.name)
        assertEquals(local.participantCount, published.participantCount)
    }

    @Test(expected = IllegalStateException::class)
    fun publishedKitIsNotPublishedTwice() {
        kit(publication = MedKit.Publication.PUBLISHED).published()
    }

    /** Общей полка становится только по своему решению: без пометки отвечать нечему. */
    @Test(expected = IllegalStateException::class)
    fun aShelfNobodyDecidedToPublishDoesNotBecomeShared() {
        kit(publication = MedKit.Publication.LOCAL).published()
    }

    @Test
    fun thereAreExactlyTwoPublicationStates() {
        // Половины не бывает: либо аптечка на сервере целиком, либо её там нет (PLAN E5).
        assertEquals(listOf("LOCAL", "PUBLISHED"), MedKit.Publication.entries.map { it.name })
    }

    @Test
    fun kitLeftByEveryoneElseStaysOnTheServer() {
        // Там лежат мои пачки, и локальной она уже не станет: PUBLISHED не выводится из числа
        // участников, а хранится отдельно.
        val alone = kit(publication = MedKit.Publication.PUBLISHED, participants = 1)
        assertFalse(alone.isShared)
        assertTrue(alone.acceptsInvitations)
    }

    @Test
    fun sharedKitIsTheOneWithOtherParticipants() {
        assertTrue(kit(publication = MedKit.Publication.PUBLISHED, participants = 2).isShared)
    }

    @Test(expected = IllegalArgumentException::class)
    fun localKitCannotHaveOtherParticipants() {
        kit(publication = MedKit.Publication.LOCAL, participants = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNameIsRejected() {
        kit(name = "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyLocationIsNotAWayToSayThereIsNone() {
        kit(location = "")
    }

    @Test
    fun absentLocationIsNull() {
        assertTrue(kit(location = null).location == null)
    }

    @Test
    fun nameFillingTheLimitFits() {
        kit(name = "я".repeat(MedKit.NAME_MAX_LENGTH))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nameOverTheLimitIsRejected() {
        kit(name = "я".repeat(MedKit.NAME_MAX_LENGTH + 1))
    }

    private fun kit(
        name: String = "Домашняя",
        location: String? = "верхняя полка",
        publication: MedKit.Publication = MedKit.Publication.LOCAL,
        participants: Long = 1
    ) = MedKit(
        id = HOME_KIT,
        name = name,
        location = location,
        publication = publication,
        participantCount = participants,
        createdAt = Instant.EPOCH
    )
}

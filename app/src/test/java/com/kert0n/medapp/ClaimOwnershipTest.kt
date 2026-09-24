package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Бронь считается разницей, и ставит её **один** владелец — держит проверка, а не внимание
 * (PLAN D5, E2).
 *
 * Правило одно: `Course.claimChangesSince(before)` — что изменилось у каждой пачки между двумя
 * состояниями лечения. Поручения по нему выдаёт очередь (`QueueService.claims`), а просит их
 * единственный сценарий — `CourseFollowing.announceClaims`; начало лечения считает от
 * `Course.unallocated()`, конец — к нему, правка и зажим — между двумя редакциями. Стоит второму сценарию посчитать разницу
 * самому — и два счётчика разойдутся: один поставит бронь по ненулевым выделениям, другой снимет
 * по всем источникам, третий забудет про пачку, которую человек убрал из состава.
 *
 * Названы поимённо два исключения, где команда брони — **не разница**, а часть чужого
 * объявления, и выдаёт их тоже очередь: снятие брони зависимым от расхода последней дозы
 * (`QueueService.consumption` — полка узнаёт о конце коробки одним ответом) и бронь новой коробки
 * зависимой от её создания на новой полке (`QueueService.announcement`).
 */
class ClaimOwnershipTest {

    /**
     * Кому позволено **собирать** команду брони: объявлению, правилу разницы, чтению из хранения
     * и двум названным объявлениям. Сценарии курса команду не собирают вовсе — они отдают
     * владельцу два состояния.
     */
    private val mayCommand: Set<String> = setOf(
        "queue/pack/PackageSyncCommand.kt",
        "queue/pack/ClaimChanges.kt",
        "storage/operation/SyncCommandStorageConverter.kt",
        "queue/QueueService.kt"
    )

    /** Кому позволено считать разницу: правилу и его владельцу. */
    private val mayDiff: Set<String> = setOf(
        "queue/pack/ClaimChanges.kt",
        "queue/QueueService.kt"
    )

    /** Кому позволено просить поручения разницы: одному сценарию. */
    private val mayAskClaims: Set<String> = setOf("feature/course/CourseFollowing.kt")

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    @Test
    fun theOwnersAreWhereWeThinkTheyAre() {
        for (allowed in mayCommand + mayDiff + mayAskClaims) {
            val file = File(sources, allowed)
            assertTrue("нет файла $allowed — проверка сторожила бы пустоту", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun onlyTheNamedPlacesCommandAClaim() {
        assertEquals(
            "команду брони ставят мимо владельца — бронь считается разницей одним местом",
            mayCommand.toSortedSet(),
            callersOf(Regex("\\b(SetClaim|ReleaseClaim)\\s*\\("))
        )
    }

    @Test
    fun onlyTheOwnerComputesTheDifference() {
        assertEquals(
            "разницу броней считают мимо владельца",
            mayDiff.toSortedSet(),
            callersOf(Regex("\\bclaimChangesSince\\s*\\("))
        )
    }

    @Test
    fun onlyTheOwnerAsksForTheDifference() {
        assertEquals(
            "поручения разницы броней просят мимо владельца",
            mayAskClaims.toSortedSet(),
            callersOf(Regex("\\bqueue\\.claims\\s*\\("))
        )
    }

    private fun callersOf(call: Regex) = sources.walkTopDown()
        .filter { it.extension == "kt" }
        .filter { file -> file.readText().contains(call) }
        .map { it.relativeTo(sources).invariantSeparatorsPath }
        .toSortedSet()
}

package com.kert0n.medapp.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строку очереди меняет одна дверь (PLAN C1 «Переходы операции — у типа»): состояние отправки
 * решает `SyncOperationState` своими переходами, а хранение пишет то, что получило, —
 * `SyncOperationDao.save`, — и только строку, которую прочитало той же транзакцией. Пока глаголов
 * было шесть, у каждого был свой `WHERE` и свой набор колонок, и правило закрытия разошлось
 * (`SUPERSEDED` в журнале у утраты доступа). Вторая дверь — `dismiss`: отметка человека о закрытой
 * строке, не переход состояния. Проверка читает исходники: новый `UPDATE sync_operations` она
 * назовёт файлом, а сравнение статуса с константой вне типа — строкой.
 */
class QueueWriteDoorTest {

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    private fun kotlin(): Sequence<File> = sources.walkTopDown().filter { it.extension == "kt" }

    /** Ровно две двери, обе в DAO: переход состояния и отметка человека. */
    @Test
    fun theOperationRowIsChangedThroughExactlyTwoDoors() {
        val doors = kotlin().flatMap { file ->
            Regex("""UPDATE sync_operations SET (\w+)""").findAll(file.readText()).map { "${file.relativeTo(sources).invariantSeparatorsPath}: SET ${it.groupValues[1]}…" }
        }.sorted().toList()
        assertEquals(
            "строку очереди меняют мимо переходов состояния",
            listOf(
                "storage/server/SyncOperationDao.kt: SET dismissed_at…",
                "storage/server/SyncOperationDao.kt: SET status…"
            ),
            doors
        )
    }

    /**
     * Предусловие перехода — у типа: никто, кроме него, не спрашивает «в каком статусе строка»,
     * чтобы решить, что с ней делать. Что закрыто, отвечает `SyncOperationStatus.isClosed`; ждёт ли
     * человека — `StoredSyncOperation.needsDecision`; ждёт ли применения — `awaitsApplication`.
     */
    @Test
    fun onlyTheStateComparesStatusesToDecideATransition() {
        val allowed = setOf(
            "queue/SyncOperationState.kt",
            "queue/SyncOperationStatus.kt",
            "queue/StoredSyncOperation.kt",
            // Описание перехода называет свой статус, не сравнивает.
            "queue/Settlement.kt"
        )
        val offenders = kotlin()
            .map { it.relativeTo(sources).invariantSeparatorsPath to it.readText() }
            .filter { (path, _) -> path !in allowed }
            .flatMap { (path, text) ->
                text.lineSequence().withIndex()
                    .filter { (_, line) -> Regex("""[=!]=\s*SyncOperationStatus\.[A-Z_]+|SyncOperationStatus\.[A-Z_]+\s*[=!]=""").containsMatchIn(line) }
                    .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertEquals("статус сравнивают мимо переходов состояния", emptyList<String>(), offenders)
    }

    /** Каждая запись хранилища очереди — чтение, переход и запись одной транзакцией (F5). */
    @Test
    fun everyQueueWriteReadsAndWritesInOneTransaction() {
        val text = File(sources, "storage/server/QueueRoomStorage.kt").readText()
        val bodies = Regex("""override suspend fun (take|answered|defer|settle)\(.*?\n(.*?)\n    (?=override|private|/\*\*|companion)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("take", "answered", "defer", "settle"), bodies.keys)
        val outside = bodies.filterValues { !it.contains("withTransaction") && !it.contains("transaction {") }.keys
        assertEquals("запись хранилища очереди вне транзакции", emptySet<String>(), outside)
    }
}

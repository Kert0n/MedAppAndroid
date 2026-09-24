package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Решает тот, чей это язык, — держит проверка, а не внимание (AGENTS «Описание», шаг 2).
 *
 * Сценарий ведёт: читает, передаёт, пишет и ветвится по **значению** решения. Что сказать
 * общему реестру и как ляжет переход — сразу или ждать сервера — решает очередь; отказ с причиной
 * рождается у домена или у очереди. Стоит сценарию самому собрать команду или спросить, отвечает
 * ли полка серверу, — и правило поручений живёт в десяти местах, а не в одном.
 */
class DecisionOwnershipTest {

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    /** Поручение собирает очередь; хранение только поднимает записанное из колонок. */
    @Test
    fun onlyTheQueueAssemblesErrands() {
        assertEquals(
            "команды реестру собираются мимо очереди",
            sortedSetOf<String>(),
            filesOutside(setOf("queue/", "storage/operation/"), Regex("\\bQueuedCommand\\s*\\(|\\b(Package|MedKit)SyncCommand\\.[A-Z]\\w*\\s*\\("))
        )
    }

    /** Отвечает ли полка серверу — вопрос очереди и домена, а не сценария. */
    @Test
    fun scenariosDoNotAskWhomTheShelfAnswers() {
        assertEquals(
            "сценарий сам решает «местная или общая»",
            sortedSetOf<String>(),
            filesUnder("feature/", Regex("\\b(answersToServer|acceptsCommands)\\b"))
        )
    }

    /**
     * Причина отказа домена и очереди рождается там, где решают. Сценарий и экран её только
     * сравнивают и называют — `==`, `!=`, голова ветки `when`.
     */
    @Test
    fun refusalsAreBornWhereTheyAreDecided() {
        val born = Regex("(?<!==\\s)(?<!!=\\s)\\b(\\w+Rejected\\.Reason|RefusalReason)\\.[A-Z][A-Z_]+\\b")
        assertEquals(
            "причина отказа рождается в сценарии или на экране",
            sortedSetOf<String>(),
            filesUnder("feature/", born, withoutBranchHeads = true) + filesUnder("presentation/", born, withoutBranchHeads = true)
        )
    }

    /**
     * Сценарий ветвится по **ответу** вещи на заданный ей вопрос — `refuses…()`, `Result`, решение
     * очереди, — но не читает её состояние сам и не складывает правило из фактов: пригодна ли
     * коробка, занята ли полка решением, идёт ли лечение, та ли это полка, кончилась ли коробка.
     * Такое правило, собранное в сценарии, повторяется в каждом, кто о вещи спрашивает.
     * Исключения — типы сценария, держащие свой инвариант: [mayReadState].
     */
    @Test
    fun scenariosAskThingsInsteadOfReadingThem() {
        val reads = Regex(
            "\\.status\\.(allowsUse|allowsDecision)\\b|\\bstatus\\s*(==|!=)\\s*IntakeStatus\\.|" +
                "\\bstate\\s*(==|!=)\\s*Reminder\\.State\\.|\\.id\\s*[!=]=\\s*[\\w.]*medKit\\.id\\b|\\.isZero\\b"
        )
        assertEquals(
            "сценарий сам читает состояние вещи",
            sortedSetOf<String>(),
            filesUnder("feature/", reads) - mayReadState
        )
    }

    private val mayReadState: Set<String> = setOf(
        "feature/intake/RecordedIntake.kt",
        "feature/intake/IntakeOutcome.kt"
    )

    private fun filesUnder(root: String, pattern: Regex, withoutBranchHeads: Boolean = false) = kotlinFiles()
        .filter { (path, _) -> path.startsWith(root) }
        .filter { (_, text) -> (if (withoutBranchHeads) text.replace(branchHead, "") else text).contains(pattern) }
        .mapTo(sortedSetOf()) { it.first }

    /** Голова ветки `when` — перечень значений до `->` или до переноса после запятой: это чтение. */
    private val branchHead = Regex("(?m)^\\s*[\\w.]+(\\s*,\\s*[\\w.]+)*\\s*(,\\s*$|->)")

    private fun filesOutside(roots: Set<String>, pattern: Regex) = kotlinFiles()
        .filter { (path, _) -> roots.none { path.startsWith(it) } }
        .filter { (_, text) -> text.contains(pattern) }
        .mapTo(sortedSetOf()) { it.first }

    /** Код без комментариев: KDoc, называющий команду, её не собирает. */
    private fun kotlinFiles(): Sequence<Pair<String, String>> = sources.walkTopDown()
        .filter { it.extension == "kt" }
        .map { it.relativeTo(sources).invariantSeparatorsPath to code(it.readText()) }

    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
}

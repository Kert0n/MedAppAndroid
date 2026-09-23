package com.kert0n.medapp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Границы слоёв держатся проверкой, а не вниманием. Раньше их стерегли грепы AGENTS, и это
 * работало ровно до тех пор, пока кто-нибудь не забывал их запустить.
 *
 * Правило одно: **домен — центр всего**. Действие продукта называет домен, а выполняет тот корень,
 * который умеет; зависимости идут внутрь, к домену, и никогда наружу (PLAN H1).
 *
 * Здесь проверяется то, что действительно нельзя, а не то, что сейчас случайно не встречается:
 * список разрешённого у каждого корня — это его договор из таблицы H1, и расширять его нужно
 * осознанно, а не «чтобы собралось».
 */
class LayerBoundariesTest {

    /**
     * Кому что позволено видеть — таблица H1 «кто чем владеет и кто кого видит». Пусто — не видит
     * никого, кроме себя, `java` и `kotlin`.
     *
     * `di` отсутствует в списках намеренно: это проводка, она собирает граф и по построению видит
     * всех. Зато её самой не видит никто, кроме тех, кому нужны её определители (`@MedAppHttp`,
     * `@IoDispatcher`).
     *
     * Ключ — путь от корня пакета, и более длинный побеждает: `app/navigation` рисует маршруты и
     * живёт по правилам экрана, а не точки входа.
     */
    private val maySee: Map<String, Set<String>> = mapOf(
        // Домен не знает ни Android, ни Room, ни Ktor, ни остальных корней.
        "domain" to emptySet(),
        // Провод: запросы и ответы. Исполняет курьера очереди — собрать посылку, довезти её,
        // механически повторив ту же, и вернуть статус на языке поручения.
        "network" to setOf("domain", "queue"),
        // Поручения общему реестру: что сказать серверу, в каком порядке, какова судьба и что она
        // значит. Про провод и таблицы очередь не знает — курьера и журнал она объявляет портами.
        "queue" to setOf("domain"),
        // Данные: как сохранить и отдать требуемое. Порты сценария исполняет, очередь не видит.
        "storage" to setOf("domain", "feature"),
        // Журнал поручений: раскладывает типы очереди по колонкам и собирает обратно, но не толкует.
        "storage/operation" to setOf("domain", "queue", "feature"),
        // Уходит в PR B вместе с портами пачки и приёма (решение владельца 2026-09-24): знание о
        // сервере в строках пачки и приёма и свёртка поручений в проекции для экрана.
        "storage/pack" to setOf("domain", "feature", "queue"),
        "storage/intake" to setOf("domain", "feature", "queue"),
        // Сценарий стоит над логиками, но в сеть не ходит: сетевое действие называет домен портом.
        "feature" to setOf("domain", "queue", "storage"),
        // Android-службы без экранов: фон, ключи, уведомления — и порты, которые они исполняют.
        "platform" to setOf("domain", "network", "queue", "storage", "feature"),
        // Представление строит состояние экрана из доменных величин, зовёт сценарии и читает порты.
        "presentation" to setOf("domain", "feature", "storage", "platform"),
        // Составляющие экрана рисуют доменные значения и готовые DTO представления.
        "ui" to setOf("domain", "presentation"),
        // Маршруты — часть экрана, а не точки входа: объектов в них не ездит (PLAN H3, G3).
        "app/navigation" to setOf("domain", "presentation", "ui"),
        // Точка входа собирает всё вместе.
        "app" to setOf("domain", "presentation", "ui", "feature", "platform", "queue", "storage")
    )

    private val sources: File = listOf(
        File("src/main/java/com/kert0n/medapp"),
        File("app/src/main/java/com/kert0n/medapp")
    ).firstOrNull { it.isDirectory } ?: error("исходники не найдены: проверка прошла бы впустую")

    private val roots: Set<String> = maySee.keys.map { it.substringBefore('/') }.toSet()

    /** Проверка, которая не должна пройти впустую: дерево на месте и оно не пустое. */
    @Test
    fun theSourceTreeIsWhereWeThinkItIs() {
        val files = sources.walkTopDown().filter { it.extension == "kt" }.count()

        assertTrue("файлов найдено $files — дерево не то", files > 200)
        for (root in roots) {
            assertTrue("корня $root нет", File(sources, root).isDirectory)
        }
    }

    /**
     * Зависимости идут внутрь. Нарушение называется файлом и ребром, чтобы его не пришлось искать:
     * «что-то где-то импортирует лишнее» — не отчёт.
     *
     * Считается не только `import`: полное имя в теле — тот же переход границы, и прятать его за
     * `com.kert0n.medapp.queue.RefusalReason` в коде хранения ничем не лучше (замечание разбора
     * #16, которое сама та ветка не выполнила).
     *
     * Красная проверка: позволить `feature` видеть `network` — падают ровно те файлы, которые
     * пошли бы в сеть мимо доменного порта.
     */
    @Test
    fun dependenciesPointInwards() {
        val broken = mutableListOf<String>()
        for (file in sources.walkTopDown().filter { it.extension == "kt" }) {
            val from = file.root() ?: continue
            val allowed = maySee.getValue(from)
            for (used in file.namedRoots()) {
                // Определители проводки (`@MedAppHttp`, `@IoDispatcher`) нужны тому, кого она
                // собирает: `di` видит всех по построению и границей не считается.
                if (used == "di") continue
                if (used != from.substringBefore('/') && used !in allowed) {
                    broken += "${file.relativeTo(sources)}: $from → $used"
                }
            }
        }

        assertEquals(emptyList<String>(), broken.distinct().sorted())
    }

    /**
     * Хранение хранит поручения, но не понимает их: из очереди оно берёт **типы** — что раскладывать
     * по колонкам, — и никогда **функции**. Свёртка, подготовка запроса и толкование исхода —
     * решения очереди; хранение, которое их зовёт, восстанавливает очередь за неё, и тогда у одного
     * правила два исполнителя. Функция узнаётся по имени со строчной буквы после пакета очереди.
     */
    @Test
    fun storageKeepsTheQueueTypesButNotItsDecisions() {
        val offenders = sources.resolve("storage").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    QUEUE_NAME.findAll(line)
                        .map { it.groupValues[1] }
                        .filter { it.first().isLowerCase() }
                        .map { "${file.relativeTo(sources).invariantSeparatorsPath}:${index + 1}: $it" }
                }
            }
            .toList()

        assertEquals("хранение зовёт решения очереди", emptyList<String>(), offenders)
    }

    /**
     * Домен независим от Android, Room и Ktor (AGENTS «Инварианты», PLAN H1): он импортирует
     * только `java.*`, `kotlin.*` и себя. Правило о корнях проекта держит [dependenciesPointInwards],
     * но чужая библиотека — не корень, и без этой проверки `android.os.Build` в домене прошёл бы.
     */
    @Test
    fun theDomainImportsNothingForeign() {
        val foreign = sources.resolve("domain").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> line.startsWith("import ") }
                    .map { (index, line) -> Triple(file, index + 1, line.removePrefix("import ").trim()) }
            }
            .filterNot { (_, _, name) ->
                name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("com.kert0n.medapp.domain.")
            }
            .map { (file, line, name) -> "${file.relativeTo(sources).invariantSeparatorsPath}:$line: import $name" }
            .toList()

        assertEquals("домен импортирует чужое", emptyList<String>(), foreign)
    }

    /**
     * Каталог называет понятие, а не вид файла (PLAN H1): `domain/pack/`, а не `domain/model/`.
     * Что это DTO, маппер или строка таблицы, видно по имени типа, а слой — по корню.
     */
    @Test
    fun directoriesNameConceptsNotMechanisms() {
        val mechanisms = setOf(
            "model", "calc", "sync", "mapper", "dto", "remote", "local", "entity", "dao",
            "repository", "util", "utils", "helper", "helpers", "common", "core", "misc"
        )
        val named = sources.walkTopDown()
            .filter { it.isDirectory && it.name in mechanisms }
            .map { it.relativeTo(sources).path }
            .toList()

        assertEquals(emptyList<String>(), named)
    }

    /**
     * Сценарий отвечает на вход исходом, а не падением (PLAN D6, F5). Правило одно на `feature/`:
     * то, что пришло **снаружи** — идентификатор с экрана, из шторки, из ответа сервера, — может
     * пропасть, пока его несли, и на это есть исход (`Gone`, `GONE`, `Rejected`); то, что прочитано
     * **этой же транзакцией**, — инвариант F5, и его держит `readThisTransaction` (правило одним
     * местом в `queue/`); что следует из уже прочитанного — `checkNotNull` с текстом инварианта:
     * «записан», «у … есть …». Поэтому `requireNotNull` в сценарии не бывает вовсе.
     */
    @Test
    fun aScenarioAnswersAMissingInputWithAnOutcome() {
        val invariant = Regex("этой же транзакцией|записан|^у .+ есть ")
        val offenders = sources.resolve("feature").walkTopDown()
            .filter { it.extension == "kt" }
            // Само правило F5 держит свой `checkNotNull` — оно и есть «прочитано этой же транзакцией».
            .filter { it.name != "ReadThisTransaction.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> "requireNotNull(" in line || "checkNotNull(" in line }
                    .filterNot { (_, line) ->
                        "checkNotNull(" in line && CHECK_MESSAGE.find(line)?.groupValues?.get(1)?.contains(invariant) == true
                    }
                    .map { (index, line) -> "${file.relativeTo(sources).invariantSeparatorsPath}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertEquals("пропавший вход сценария — исход, а не падение", emptyList<String>(), offenders)
    }

    /**
     * Курс следует за коробкой у **одного** владельца — `feature/course` (PLAN C1 «Конец коробки —
     * у владельца реакции»): переходы курса, которыми лечение отвечает на коробку — отсоединить
     * источник, зажать выделения, отключить и вернуть источник, — зовёт только он. Пока конец
     * коробки отсоединял источник расширением DAO, у одного изменения было два входа с разными
     * следствиями: без события сокращения, без броней.
     */
    @Test
    fun courseTransitionsBelongToTheirOwner() {
        val transitions = Regex("""\.(detach|clamped|faultSource|restoreSource)\(""")
        val offenders = sources.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file ->
                val path = file.relativeTo(sources).invariantSeparatorsPath
                !path.startsWith("feature/course/") && !path.startsWith("domain/")
            }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> transitions.containsMatchIn(line) }
                    .map { (index, line) -> "${file.relativeTo(sources).invariantSeparatorsPath}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertEquals("переход курса зовут мимо владельца реакции", emptyList<String>(), offenders)
    }

    /** Чей это файл: самый длинный подходящий ключ, чтобы `app/navigation` не считался `app`. */
    private fun File.root(): String? {
        val path = relativeTo(sources).path
        return maySee.keys.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
    }

    /** Корни, которые называет файл: и в `import`, и полным именем в теле. */
    private fun File.namedRoots(): Set<String> = readLines()
        .flatMapTo(HashSet()) { line -> NAMED.findAll(line).map { it.groupValues[1] } }
        .filterTo(HashSet()) { it in roots || it == "di" }

    private companion object {
        val NAMED = Regex("""com\.kert0n\.medapp\.([a-z]+)\.""")
        /** Последнее имя после пакета очереди (и его подпакетов): тип — с заглавной, функция — нет. */
        val QUEUE_NAME = Regex("""com\.kert0n\.medapp\.queue(?:\.[a-z][A-Za-z0-9]*)*\.([A-Za-z][A-Za-z0-9]*)(?![A-Za-z0-9.])""")
        val CHECK_MESSAGE = Regex("""checkNotNull\(.*\)\s*\{\s*"([^"]*)"""")
    }
}

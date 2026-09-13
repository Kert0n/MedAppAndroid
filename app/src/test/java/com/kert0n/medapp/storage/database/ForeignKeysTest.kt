package com.kert0n.medapp.storage.database

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поведение ключа называет отношение, а не осторожность (PLAN F2): часть живой коробки уходит с
 * ней, связь с чужим агрегатом снимает домен, история держится за запись и не удаляется,
 * неудаляемое не удаляется. Договор записан здесь и сверяется с экспортированной схемой — как
 * границы слоёв сверяются с импортами: каскад, поставленный «на всякий случай», уносит историю
 * или меняет чужой агрегат мимо его редакции, а `RESTRICT` там же запрещает человеку выбросить
 * коробку, и ни то ни другое не видно в ревью без такой проверки.
 *
 * Незнакомый ключ — падение: договор пополняется явно, а не молчаливым умолчанием.
 */
class ForeignKeysTest {

    /** Как ключ ведёт себя при удалении родителя — и почему. */
    private enum class Relation(val onDelete: String) {
        /** Часть живой коробки: без неё не значит ничего. */
        PART("CASCADE"),
        /**
         * Связь с чужим агрегатом: её снимает домен своим переходом, а не схема. Каскад дал бы
         * верный набор строк при неверной редакции — чужой агрегат не узнал бы, что изменился.
         */
        BOND("RESTRICT"),
        /** История держится за запись и не удаляется никогда. */
        HISTORY("RESTRICT"),
        /** Родителя не удаляют, пока на него ссылаются: словарь, аптечка под пачками, запись. */
        UNREMOVABLE("RESTRICT")
    }

    /** `таблица.колонка → родитель`. */
    private val contract: Map<String, Relation> = mapOf(
        // Запись о коробке держит словарь; живая коробка держится за запись, аптечку и словарь.
        "package_records.unit_id → quantity_units" to Relation.UNREMOVABLE,
        "package_records.form_id → form_types" to Relation.UNREMOVABLE,
        "drug_templates.quantity_unit_id → quantity_units" to Relation.UNREMOVABLE,
        "drug_templates.form_id → form_types" to Relation.UNREMOVABLE,
        "packages.id → package_records" to Relation.UNREMOVABLE,
        "packages.med_kit_id → med_kits" to Relation.UNREMOVABLE,
        "packages.quantity_unit_id → quantity_units" to Relation.UNREMOVABLE,
        "packages.form_id → form_types" to Relation.UNREMOVABLE,
        // Части живой коробки уходят с ней (D3).
        "package_details.package_id → packages" to Relation.PART,
        "claims.package_id → packages" to Relation.PART,
        // Состав лечения и занятость пачки — состояние курса, и снимает их доменный переход (D5).
        "course_sources.package_id → packages" to Relation.BOND,
        "active_package_assignments.package_id → packages" to Relation.BOND,
        // Состав уходит вместе с планом, но не молча: его снимает транзакция конца лечения (F5).
        "course_sources.course_id → courses" to Relation.UNREMOVABLE,
        "active_package_assignments.course_id → courses" to Relation.UNREMOVABLE,
        // История — приёмы — держится за записи: о коробке и об эпизоде (D6).
        "intakes.course_id → course_records" to Relation.HISTORY,
        "intakes.planned_package_id → package_records" to Relation.HISTORY,
        "intakes.taken_package_id → package_records" to Relation.HISTORY,
        // Событие сокращения — история: переживает и конец лечения, и конец коробки (PLAN D5).
        "coverage_reductions.course_id → course_records" to Relation.HISTORY,
        "coverage_reductions.package_id → package_records" to Relation.HISTORY,
        // Зависимости очереди: операцию с зависимыми не удаляют.
        "sync_operation_dependencies.operation_id → sync_operations" to Relation.UNREMOVABLE,
        "sync_operation_dependencies.depends_on_id → sync_operations" to Relation.UNREMOVABLE
    )

    private val schema: File = listOf("schemas", "app/schemas")
        .map { File(it, "com.kert0n.medapp.storage.database.MedAppDatabase/${MedAppDatabase.VERSION}.json") }
        .firstOrNull { it.isFile }
        ?: error("экспортированная схема версии ${MedAppDatabase.VERSION} не найдена: проверка прошла бы впустую")

    private fun foreignKeys(): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject
            .getValue("database").jsonObject.getValue("entities").jsonArray
        for (entity in entities.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            for (key in entity["foreignKeys"]?.jsonArray.orEmpty().map { it.jsonObject }) {
                val columns = key.getValue("columns").jsonArray.joinToString(",") { it.jsonPrimitive.content }
                val parent = key.getValue("table").jsonPrimitive.content
                found["$table.$columns → $parent"] = key.getValue("onDelete").jsonPrimitive.content
            }
        }
        return found
    }

    @Test
    fun everyForeignKeyBehavesAsItsRelationSays() {
        val found = foreignKeys()
        assertTrue("в схеме нет ни одного ключа — читается не то", found.isNotEmpty())
        val unknown = found.keys - contract.keys
        assertEquals("ключи без договора: $unknown", emptySet<String>(), unknown)
        val missing = contract.keys - found.keys
        assertEquals("договор называет ключи, которых в схеме нет: $missing", emptySet<String>(), missing)
        for ((key, relation) in contract) {
            assertEquals(key, relation.onDelete, found.getValue(key))
        }
    }
}

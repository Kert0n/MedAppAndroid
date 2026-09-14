package com.kert0n.medapp.storage.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.INTAKE
import com.kert0n.medapp.fixture.OTHER_PACK
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLETS_ID
import com.kert0n.medapp.fixture.TABLET_FORM_ID
import com.kert0n.medapp.fixture.fileDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.tablets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Схема переживает обновление приложения. Экспорт схемы — необходимое, но не достаточное:
 * проверка открывает базу объявленной версии и сверяет её со скомпилированной (PLAN F4).
 *
 * Переход 1→2 — колонка `outcome_unknown` у очереди (PLAN E3, F4). Переход 2→3 — коробка стала
 * живой пачкой и вечной записью: у `packages` нет состояний, история держится за
 * `package_records`, движение — запись о пачке без аптечек (D3, D7). База прошлой версии
 * переезжает целиком, а не заводится заново.
 */
class MedAppDatabaseMigrationTest {

    private val name = "migration.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MedAppDatabase::class.java
    )

    /** Экспортированная схема совпадает со скомпилированной: расхождение падает здесь. */
    @Test
    fun exportedSchemaMatchesTheCompiledOne() {
        helper.createDatabase(name, MedAppDatabase.VERSION).close()
        helper.runMigrationsAndValidate(name, MedAppDatabase.VERSION, true).close()
    }

    /**
     * Таблица, созданная переходом, совпадает с той, что заводит схема, — **по определению колонок**,
     * а не только по именам. Room сравнивает умолчания лишь тогда, когда их объявляет сущность,
     * поэтому `DEFAULT` в переходе и его отсутствие в схеме проходят мимо `runMigrationsAndValidate`
     * молча — и расходятся дальше сами по себе.
     */
    @Test
    fun theMigratedTablesMatchTheCreatedOnes() {
        fun columns(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> =
            database.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            listOf("name", "type", "notnull", "dflt_value", "pk")
                                .joinToString(" ") { column -> cursor.getString(cursor.getColumnIndexOrThrow(column)) ?: "-" }
                        )
                    }
                }
            }

        val file = "migrate-columns.db"
        helper.createDatabase(file, 2).close()
        val migrated = helper.runMigrationsAndValidate(file, 3, true, MedAppDatabase.MIGRATION_2_3)
        val fromMigration = columns(migrated, "reminders")
        migrated.close()

        val fresh = helper.createDatabase("created-columns.db", 3)
        val fromSchema = columns(fresh, "reminders")
        fresh.close()

        assertEquals("переход и схема описывают `reminders` по-разному", fromSchema, fromMigration)
    }

    /** Очередь версии 1 переезжает в версию 2 вместе со строкой; исход старой отправки — известен. */
    @Test
    fun queueOfVersionOneSurvivesTheMoveToVersionTwo() {
        val file = "migrate-1-2.db"
        helper.createDatabase(file, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO sync_operations (id, kind, payload, payload_version, sequence, status, attempts, created_at) " +
                    "VALUES ('00000000-0000-4000-8000-000000000091', 'DELETE', '{\"packageId\":\"$PACK\"}', 1, 0, 'SENDING', 2, 0)"
            )
        }

        val v2 = helper.runMigrationsAndValidate(file, 2, true, MedAppDatabase.MIGRATION_1_2)
        val row = v2.query("SELECT status, attempts, outcome_unknown FROM sync_operations")
        row.moveToFirst()
        assertEquals("SENDING", row.getString(0))
        assertEquals(2, row.getInt(1))
        assertEquals(0, row.getInt(2))
        row.close()
        v2.close()
    }

    /**
     * Версия 3 — коробка стала живой пачкой и вечной записью (PLAN D3, F1, F2). База версии 2 с
     * данными во **всех** перестроенных таблицах переезжает целиком: живая пачка — со своими
     * частями; кончившаяся — только записью, за которую держится её приём; движения уходят
     * целиком — истории у коробки нет (D7).
     */
    @Test
    fun aDatabaseOfVersionTwoMovesToVersionThreeWhole() {
        val file = "migrate-2-3.db"
        val receipt = "00000000-0000-4000-8000-000000000101"
        val transfer = "00000000-0000-4000-8000-000000000102"
        val oldReceipt = "00000000-0000-4000-8000-000000000103"
        val operation = "00000000-0000-4000-8000-000000000104"
        helper.createDatabase(file, 2).use { v2 ->
            v2.execSQL("INSERT INTO quantity_units (id, name) VALUES ('$TABLETS_ID', 'таблетки')")
            v2.execSQL("INSERT INTO form_types (id, name) VALUES ('$TABLET_FORM_ID', 'таблетки')")
            v2.execSQL(
                "INSERT INTO med_kits (id, name, publication, participant_count, created_at) " +
                    "VALUES ('$HOME_KIT', 'Домашняя', 'LOCAL', 1, 0)"
            )
            for ((id, lifecycle, quantity) in listOf(Triple(PACK, "ACTIVE", "20"), Triple(OTHER_PACK, "ARCHIVED", "0"))) {
                v2.execSQL(
                    "INSERT INTO packages (id, med_kit_id, name, name_search, quantity, quantity_sort, " +
                        "quantity_unit_id, form_id, lifecycle, access) VALUES ('$id', '$HOME_KIT', " +
                        "'Парацетамол', 'парацетамол', '$quantity', '$quantity', '$TABLETS_ID', " +
                        "'$TABLET_FORM_ID', '$lifecycle', 'AVAILABLE')"
                )
                v2.execSQL("INSERT INTO package_details (package_id, added_at, note) VALUES ('$id', 5, 'в машине')")
            }
            v2.execSQL("INSERT INTO claims (package_id, total, mine) VALUES ('$PACK', '5', '2')")
            // Отказанная операция версии 2 хранила причину текстом журнала: она переносится значением.
            v2.execSQL(
                "INSERT INTO sync_operations (id, kind, payload, payload_version, sequence, status, attempts, created_at, last_error) " +
                    "VALUES ('$operation', 'CONSUME', '{}', 1, 0, 'REFUSED', 1, 0, 'INSUFFICIENT')"
            )
            v2.execSQL(
                "INSERT INTO course_records (id, title, dose_amount, unit_id, form_id, total_doses, start, " +
                    "days_mask, zone, started_at) VALUES ('$COURSE', 'Курс', '2', '$TABLETS_ID', " +
                    "'$TABLET_FORM_ID', 10, '2026-09-01', 127, 'Europe/Moscow', 0)"
            )
            v2.execSQL(
                "INSERT INTO courses (id, dose_amount, unit_id, form_id, total_doses, taken_off_plan, start, " +
                    "days_mask, zone, revision, created_at, updated_at) VALUES ('$COURSE', '2', " +
                    "'$TABLETS_ID', '$TABLET_FORM_ID', 10, 0, '2026-09-01', 127, 'Europe/Moscow', 1, 0, 0)"
            )
            v2.execSQL(
                "INSERT INTO course_sources (course_id, package_id, position, allocated_doses) " +
                    "VALUES ('$COURSE', '$PACK', 0, 5)"
            )
            v2.execSQL("INSERT INTO active_package_assignments (package_id, course_id) VALUES ('$PACK', '$COURSE')")
            // Приём из кончившейся пачки: держится за неё и после того, как живой строки нет.
            v2.execSQL(
                "INSERT INTO intakes (id, unit_id, status, course_id, course_revision, scheduled_on, " +
                    "scheduled_time, scheduled_at, planned_amount, planned_package_id, answered_at, " +
                    "taken_package_id, taken_amount, accounting, operation_id) VALUES ('$INTAKE', " +
                    "'$TABLETS_ID', 'TAKEN', '$COURSE', 1, '2026-09-01', 540, 0, '2', '$OTHER_PACK', 7, " +
                    "'$OTHER_PACK', '2', 'LOCAL_APPLIED', '$operation')"
            )
            v2.execSQL(
                "INSERT INTO stock_adjustments " +
                    "(id, package_id, kind, unit_id, observed_at, occurred_at, amount, med_kit_id) " +
                    "VALUES ('$receipt', '$PACK', 'RECEIPT', '$TABLETS_ID', 10, 10, '20', '$HOME_KIT')"
            )
            v2.execSQL(
                "INSERT INTO stock_adjustments " +
                    "(id, package_id, kind, unit_id, observed_at, occurred_at, amount, med_kit_id) " +
                    "VALUES ('$oldReceipt', '$OTHER_PACK', 'RECEIPT', '$TABLETS_ID', 1, 1, '2', '$HOME_KIT')"
            )
            v2.execSQL(
                "INSERT INTO stock_adjustments " +
                    "(id, package_id, kind, unit_id, observed_at, occurred_at, amount, " +
                    "source_med_kit_id, target_med_kit_id) " +
                    "VALUES ('$transfer', '$PACK', 'TRANSFER', '$TABLETS_ID', 20, 20, '20', " +
                    "'$HOME_KIT', '$SHARED_KIT')"
            )
        }

        val v3 = helper.runMigrationsAndValidate(file, 3, true, MedAppDatabase.MIGRATION_2_3)

        fun rows(sql: String): List<List<String?>> = v3.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }
        // Запись — о каждой коробке, с моментом появления из деталей.
        assertEquals(
            listOf(listOf(PACK.toString(), "Парацетамол", TABLETS_ID.toString(), TABLET_FORM_ID.toString(), "5"),
                listOf(OTHER_PACK.toString(), "Парацетамол", TABLETS_ID.toString(), TABLET_FORM_ID.toString(), "5")),
            rows("SELECT id, name, unit_id, form_id, added_at FROM package_records ORDER BY id")
        )
        // Живая строка — только у коробки, которая есть; с ней её части.
        assertEquals(listOf(listOf(PACK.toString(), "20")), rows("SELECT id, quantity FROM packages"))
        // Старые вещи приходят без неподтверждённых решений: помечать их было нечем (PLAN E1).
        assertEquals(listOf(listOf("ACTIVE")), rows("SELECT status FROM packages"))
        assertEquals(listOf("ACTIVE"), rows("SELECT status FROM med_kits").map { it.single() }.distinct())
        assertEquals(listOf(listOf(PACK.toString(), "в машине")), rows("SELECT package_id, note FROM package_details"))
        assertEquals(listOf(listOf(PACK.toString(), "5", "2")), rows("SELECT package_id, total, mine FROM claims"))
        // Старые источники приходят исправными: отключать их было нечем (PLAN D5).
        assertEquals(listOf(listOf(COURSE.toString(), PACK.toString(), "0", "5", null)), rows("SELECT * FROM course_sources"))
        assertEquals(listOf(listOf(PACK.toString(), COURSE.toString())), rows("SELECT * FROM active_package_assignments"))
        // История держится за запись: приём из кончившейся коробки на месте.
        assertEquals(
            listOf(listOf(INTAKE.toString(), OTHER_PACK.toString(), OTHER_PACK.toString(), "2", "LOCAL_APPLIED", operation)),
            rows("SELECT id, planned_package_id, taken_package_id, taken_amount, accounting, operation_id FROM intakes")
        )
        // Причина отказа — значением у отказанной; у остальных её нет.
        assertEquals(listOf(listOf(operation, "INSUFFICIENT")), rows("SELECT id, refusal_reason FROM sync_operations"))
        // Движений версии 2 — прихода, переноса — после миграции нет вместе с таблицей.
        assertEquals(emptyList<List<String?>>(), rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'stock_adjustments'"))
        // Истраченное за год читается по моменту ответа, а не перебором (PLAN H6).
        assertEquals(
            listOf(listOf("index_intakes_answered_at")),
            rows("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_intakes_answered_at'")
        )
        v3.close()
    }

    /**
     * Разрушающий откат не включён. Файл более новой версии не стирается молча — иначе
     * обновление приложения теряло бы очередь и историю (PLAN F4).
     */
    @Test
    fun aNewerDatabaseFileIsRefusedInsteadOfWiped() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "refuses-downgrade.db"

        // Засеянная база: пачка не пишется без аптечки и словаря, ключи это держат (F2).
        val written = fileDatabase(file)
        val paracetamol = pack(quantity = tablets("20"))
        written.packages().save(paracetamol)
        written.openHelper.writableDatabase.execSQL("PRAGMA user_version = 99")
        written.close()

        val reopened = Room.databaseBuilder(context, MedAppDatabase::class.java, file).build()
        val refusal = runCatching { reopened.packages().find(PACK) }.exceptionOrNull()
        reopened.close()

        assertNotNull("более новая база открылась как ни в чём не бывало", refusal)
        assertEquals(IllegalStateException::class, refusal!!::class)

        // Отказ не должен быть уничтожением: строка на месте, её просто не отдали.
        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(file).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        val left = raw.rawQuery("SELECT COUNT(*) FROM packages", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        raw.close()
        context.deleteDatabase(file)

        assertEquals("данные стёрлись вместо отказа открыть базу", 1, left)
    }
}

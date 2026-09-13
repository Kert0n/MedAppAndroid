package com.kert0n.medapp.storage.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.kert0n.medapp.storage.course.ActivePackageAssignmentStorageEntity
import com.kert0n.medapp.storage.course.CourseDao
import com.kert0n.medapp.storage.course.CourseRecordStorageEntity
import com.kert0n.medapp.storage.course.CourseSourceStorageEntity
import com.kert0n.medapp.storage.course.CourseStorageEntity
import com.kert0n.medapp.storage.course.CourseTimeStorageEntity
import com.kert0n.medapp.storage.intake.IntakeDao
import com.kert0n.medapp.storage.intake.IntakeStorageEntity
import com.kert0n.medapp.storage.medkit.MedKitDao
import com.kert0n.medapp.storage.medkit.MedKitStorageEntity
import com.kert0n.medapp.storage.pack.ClaimsStorageEntity
import com.kert0n.medapp.storage.pack.PackageDao
import com.kert0n.medapp.storage.pack.PackageDetailsStorageEntity
import com.kert0n.medapp.storage.pack.PackageRecordStorageEntity
import com.kert0n.medapp.storage.pack.PackageStorageEntity
import com.kert0n.medapp.storage.server.NotificationLogDao
import com.kert0n.medapp.storage.server.NotificationLogStorageEntity
import com.kert0n.medapp.storage.server.SyncOperationDao
import com.kert0n.medapp.storage.server.SyncOperationDependencyStorageEntity
import com.kert0n.medapp.storage.server.SyncOperationStorageEntity
import com.kert0n.medapp.storage.template.PackageTemplateDao
import com.kert0n.medapp.storage.template.PackageTemplateStorageEntity
import com.kert0n.medapp.storage.value.DosageFormStorageEntity
import com.kert0n.medapp.storage.value.QuantityUnitStorageEntity
import com.kert0n.medapp.storage.value.VocabularyDao

/**
 * Локальная база приложения. Схема экспортируется в `app/schemas` и лежит в репозитории:
 * без прошлой версии рядом переход нечем проверить, а разрушающий откат запрещён — потерять
 * очередь и историю при обновлении приложения нельзя (PLAN F4).
 */
@Database(
    entities = [
        QuantityUnitStorageEntity::class,
        DosageFormStorageEntity::class,
        PackageTemplateStorageEntity::class,
        MedKitStorageEntity::class,
        PackageRecordStorageEntity::class,
        PackageStorageEntity::class,
        PackageDetailsStorageEntity::class,
        ClaimsStorageEntity::class,
        CourseStorageEntity::class,
        CourseRecordStorageEntity::class,
        CourseTimeStorageEntity::class,
        CourseSourceStorageEntity::class,
        ActivePackageAssignmentStorageEntity::class,
        IntakeStorageEntity::class,
        SyncOperationStorageEntity::class,
        SyncOperationDependencyStorageEntity::class,
        NotificationLogStorageEntity::class
    ],
    version = MedAppDatabase.VERSION,
    exportSchema = true
)
@TypeConverters(MedAppConverters::class)
abstract class MedAppDatabase : RoomDatabase() {

    abstract fun medKits(): MedKitDao

    abstract fun packages(): PackageDao

    abstract fun courses(): CourseDao

    abstract fun intakes(): IntakeDao

    abstract fun syncOperations(): SyncOperationDao

    abstract fun notificationLog(): NotificationLogDao

    abstract fun vocabulary(): VocabularyDao

    abstract fun templates(): PackageTemplateDao

    companion object {
        const val VERSION = 3
        const val NAME = "medapp.db"

        /** Факт «замороженный запрос уходил, исход неизвестен» получил свою колонку (PLAN E3). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE sync_operations ADD COLUMN outcome_unknown INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Коробка — живая пачка и вечная запись (PLAN D3, F1, F2):
         *
         * - появляется `package_records`: запись о каждой пачке версии 2, включая кончившиеся и
         *   утраченные, с `added_at` из деталей;
         * - у `packages` нет `lifecycle` и `access`, а сама строка держится за запись: кончившаяся
         *   и утраченная коробка строки не имеют — такие строки не переезжают;
         * - приёмы держатся за запись (`RESTRICT`); части живой коробки — сведения и брони — уходят
         *   вместе с ней (`CASCADE`), а связи с лечением снимает домен, и схема их держит
         *   (`RESTRICT`): состав курса не меняется мимо самого курса;
         * - истории коробки нет: таблица движений `stock_adjustments` уходит (D7);
         * - у коробки и аптечки появился статус — неподтверждённое решение о них (E1, E6). До версии
         *   3 решений в пути не было, поэтому все переезжают обычными.
         *
         * Ни убрать колонку с внешним ключом, ни поменять его поведение SQLite не умеет, поэтому
         * каждая задетая таблица пересоздаётся и переливается; части переливаются только у
         * выживших пачек.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                // Найденное в справочнике остаётся доступным без сети (PLAN F1, H5).
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `drug_templates` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `name_lat` TEXT,
                        `active_substance` TEXT, `form_id` TEXT, `category` TEXT,
                        `quantity_unit_id` TEXT, `manufacturer` TEXT, `country` TEXT,
                        `description` TEXT, `search_text` TEXT NOT NULL, `cached_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`quantity_unit_id`) REFERENCES `quantity_units`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT ,
                        FOREIGN KEY(`form_id`) REFERENCES `form_types`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_drug_templates_quantity_unit_id` ON `drug_templates` (`quantity_unit_id`)"
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_drug_templates_form_id` ON `drug_templates` (`form_id`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_drug_templates_cached_at` ON `drug_templates` (`cached_at`)")
                // Неподтверждённое решение об аптечке лежит на ней самой (PLAN E5, E6).
                connection.execSQL(
                    "ALTER TABLE `med_kits` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'"
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `package_records` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `unit_id` TEXT NOT NULL,
                        `form_id` TEXT, `added_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`unit_id`) REFERENCES `quantity_units`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT ,
                        FOREIGN KEY(`form_id`) REFERENCES `form_types`(`id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent()
                )
                // Запись — о каждой коробке, какой она была: за неё держится история и тех
                // коробок, чьей живой строки дальше не будет.
                connection.execSQL(
                    """
                    INSERT INTO `package_records` (`id`, `name`, `unit_id`, `form_id`, `added_at`)
                    SELECT p.`id`, p.`name`, p.`quantity_unit_id`, p.`form_id`, d.`added_at`
                    FROM `packages` p JOIN `package_details` d ON d.`package_id` = p.`id`
                    """.trimIndent()
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_package_records_unit_id` ON `package_records` (`unit_id`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_package_records_form_id` ON `package_records` (`form_id`)")
                connection.rebuild(
                    table = "packages",
                    createNew = """
                        CREATE TABLE IF NOT EXISTS `packages_new` (
                            `id` TEXT NOT NULL, `med_kit_id` TEXT NOT NULL, `name` TEXT NOT NULL,
                            `name_search` TEXT NOT NULL, `quantity` TEXT NOT NULL,
                            `quantity_sort` TEXT NOT NULL, `quantity_unit_id` TEXT NOT NULL,
                            `form_id` TEXT, `category` TEXT, `manufacturer` TEXT, `country` TEXT,
                            `description` TEXT, `version` INTEGER, `claims_version` INTEGER,
                            `synced_at` INTEGER, `status` TEXT NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`id`) REFERENCES `package_records`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT ,
                            FOREIGN KEY(`med_kit_id`) REFERENCES `med_kits`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT ,
                            FOREIGN KEY(`quantity_unit_id`) REFERENCES `quantity_units`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT ,
                            FOREIGN KEY(`form_id`) REFERENCES `form_types`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT
                        )
                    """.trimIndent(),
                    // Кончившаяся и утраченная коробка — не коробка: строки у них больше нет.
                    copy = """
                        INSERT INTO `packages_new`
                            (`id`, `med_kit_id`, `name`, `name_search`, `quantity`, `quantity_sort`,
                             `quantity_unit_id`, `form_id`, `category`, `manufacturer`, `country`,
                             `description`, `version`, `claims_version`, `synced_at`, `status`)
                        SELECT `id`, `med_kit_id`, `name`, `name_search`, `quantity`, `quantity_sort`,
                               `quantity_unit_id`, `form_id`, `category`, `manufacturer`, `country`,
                               `description`, `version`, `claims_version`, `synced_at`, 'ACTIVE'
                        FROM `packages` WHERE `lifecycle` = 'ACTIVE' AND `access` = 'AVAILABLE'
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS `index_packages_med_kit_id` ON `packages` (`med_kit_id`)",
                    "CREATE INDEX IF NOT EXISTS `index_packages_name_search` ON `packages` (`name_search`)",
                    "CREATE INDEX IF NOT EXISTS `index_packages_quantity_unit_id` ON `packages` (`quantity_unit_id`)",
                    "CREATE INDEX IF NOT EXISTS `index_packages_form_id` ON `packages` (`form_id`)"
                )
                // Движения ничего не объясняют никому: ни экран, ни отчёт их не читают (D7).
                connection.execSQL("DROP TABLE `stock_adjustments`")
                connection.rebuild(
                    table = "intakes",
                    createNew = """
                        CREATE TABLE IF NOT EXISTS `intakes_new` (
                            `id` TEXT NOT NULL, `unit_id` TEXT NOT NULL, `status` TEXT NOT NULL,
                            `course_id` TEXT, `course_revision` INTEGER, `scheduled_on` TEXT,
                            `scheduled_time` INTEGER, `scheduled_at` INTEGER,
                            `planned_amount` TEXT, `planned_package_id` TEXT, `answered_at` INTEGER,
                            `taken_package_id` TEXT, `taken_amount` TEXT,
                            `accounting` TEXT NOT NULL, `operation_id` TEXT,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`course_id`) REFERENCES `course_records`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT ,
                            FOREIGN KEY(`planned_package_id`) REFERENCES `package_records`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT ,
                            FOREIGN KEY(`taken_package_id`) REFERENCES `package_records`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT
                        )
                    """.trimIndent(),
                    copy = """
                        INSERT INTO `intakes_new`
                            (`id`, `unit_id`, `status`, `course_id`, `course_revision`, `scheduled_on`,
                             `scheduled_time`, `scheduled_at`, `planned_amount`, `planned_package_id`,
                             `answered_at`, `taken_package_id`, `taken_amount`, `accounting`, `operation_id`)
                        SELECT `id`, `unit_id`, `status`, `course_id`, `course_revision`, `scheduled_on`,
                               `scheduled_time`, `scheduled_at`, `planned_amount`, `planned_package_id`,
                               `answered_at`, `taken_package_id`, `taken_amount`, `accounting`, `operation_id`
                        FROM `intakes`
                    """.trimIndent(),
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_intakes_course_id_scheduled_on_scheduled_time` " +
                        "ON `intakes` (`course_id`, `scheduled_on`, `scheduled_time`)",
                    "CREATE INDEX IF NOT EXISTS `index_intakes_planned_package_id` " +
                        "ON `intakes` (`planned_package_id`)",
                    "CREATE INDEX IF NOT EXISTS `index_intakes_taken_package_id` " +
                        "ON `intakes` (`taken_package_id`)",
                    "CREATE INDEX IF NOT EXISTS `index_intakes_operation_id` " +
                        "ON `intakes` (`operation_id`)",
                    // Истраченное за год читается по моменту ответа, а не перебором (PLAN H6).
                    "CREATE INDEX IF NOT EXISTS `index_intakes_answered_at` " +
                        "ON `intakes` (`answered_at`)"
                )
                connection.rebuild(
                    table = "package_details",
                    createNew = """
                        CREATE TABLE IF NOT EXISTS `package_details_new` (
                            `package_id` TEXT NOT NULL, `expires_on` TEXT, `default_intake_amount` TEXT,
                            `default_intake_unit_id` TEXT, `note` TEXT, `price` TEXT,
                            `currency` TEXT, `purchased_on` TEXT, `opened_on` TEXT,
                            `template_id` TEXT,
                            PRIMARY KEY(`package_id`),
                            FOREIGN KEY(`package_id`) REFERENCES `packages`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                    """.trimIndent(),
                    copy = """
                        INSERT INTO `package_details_new`
                            (`package_id`, `expires_on`, `default_intake_amount`,
                             `default_intake_unit_id`, `note`, `price`, `currency`, `purchased_on`,
                             `opened_on`, `template_id`)
                        SELECT d.`package_id`, d.`expires_on`, d.`default_intake_amount`,
                               d.`default_intake_unit_id`, d.`note`, d.`price`, d.`currency`, d.`purchased_on`,
                               d.`opened_on`, d.`template_id`
                        FROM `package_details` d JOIN `packages` p ON p.`id` = d.`package_id`
                    """.trimIndent()
                )
                connection.rebuild(
                    table = "claims",
                    createNew = """
                        CREATE TABLE IF NOT EXISTS `claims_new` (
                            `package_id` TEXT NOT NULL, `total` TEXT NOT NULL, `mine` TEXT,
                            PRIMARY KEY(`package_id`),
                            FOREIGN KEY(`package_id`) REFERENCES `packages`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                    """.trimIndent(),
                    copy = """
                        INSERT INTO `claims_new` (`package_id`, `total`, `mine`)
                        SELECT c.`package_id`, c.`total`, c.`mine`
                        FROM `claims` c JOIN `packages` p ON p.`id` = c.`package_id`
                    """.trimIndent()
                )
                connection.rebuild(
                    table = "course_sources",
                    createNew = """
                        CREATE TABLE IF NOT EXISTS `course_sources_new` (
                            `course_id` TEXT NOT NULL, `package_id` TEXT NOT NULL,
                            `position` INTEGER NOT NULL, `allocated_doses` INTEGER NOT NULL,
                            PRIMARY KEY(`course_id`, `package_id`),
                            FOREIGN KEY(`course_id`) REFERENCES `courses`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT ,
                            FOREIGN KEY(`package_id`) REFERENCES `packages`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT
                        )
                    """.trimIndent(),
                    copy = """
                        INSERT INTO `course_sources_new` (`course_id`, `package_id`, `position`, `allocated_doses`)
                        SELECT s.`course_id`, s.`package_id`, s.`position`, s.`allocated_doses`
                        FROM `course_sources` s JOIN `packages` p ON p.`id` = s.`package_id`
                    """.trimIndent(),
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_course_sources_course_id_position` " +
                        "ON `course_sources` (`course_id`, `position`)",
                    "CREATE INDEX IF NOT EXISTS `index_course_sources_package_id` " +
                        "ON `course_sources` (`package_id`)"
                )
                connection.rebuild(
                    table = "active_package_assignments",
                    createNew = """
                        CREATE TABLE IF NOT EXISTS `active_package_assignments_new` (
                            `package_id` TEXT NOT NULL, `course_id` TEXT NOT NULL,
                            PRIMARY KEY(`package_id`),
                            FOREIGN KEY(`package_id`) REFERENCES `packages`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT ,
                            FOREIGN KEY(`course_id`) REFERENCES `courses`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT
                        )
                    """.trimIndent(),
                    copy = """
                        INSERT INTO `active_package_assignments_new` (`package_id`, `course_id`)
                        SELECT a.`package_id`, a.`course_id`
                        FROM `active_package_assignments` a JOIN `packages` p ON p.`id` = a.`package_id`
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS `index_active_package_assignments_course_id` " +
                        "ON `active_package_assignments` (`course_id`)"
                )
            }
        }

        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

/**
 * Пересоздание таблицы под новую схему: SQLite не умеет ни убрать колонку с внешним ключом, ни
 * поменять его поведение. Строки переливаются тем запросом, который называет вызывающий, и
 * называет он колонки поимённо: позиционная «звёздочка» молча перепутала бы их при любом
 * расхождении порядка. Ключи во время миграции не проверяются, поэтому родительскую таблицу
 * можно пересобрать раньше детей: они ссылаются на неё по имени.
 */
private fun SQLiteConnection.rebuild(
    table: String,
    createNew: String,
    copy: String,
    vararg indices: String
) {
    execSQL(createNew)
    execSQL(copy)
    execSQL("DROP TABLE `$table`")
    execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
    indices.forEach(::execSQL)
}

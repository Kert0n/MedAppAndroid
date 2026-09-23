package com.kert0n.medapp.storage.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kert0n.medapp.domain.value.Vocabulary
import kotlin.uuid.Uuid

/**
 * Засев словарей при создании базы: встроенный снимок ложится в таблицы вместе со схемой, и
 * первый же экран видит единицы и формы. Обновление с сервера потом переписывает имена поверх.
 * Разбирает снимок сеть — он в её форме; сюда приходят уже доменные величины.
 *
 * Пишется сырым SQL: DAO внутри создания базы открыл бы её повторно.
 */
class BundledVocabulary(private val read: () -> Vocabulary) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        val snapshot = read()
        insert(db, "quantity_units", snapshot.allUnits.map { it.id to it.name })
        insert(db, "form_types", snapshot.allForms.map { it.id to it.name })
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, rows: List<Pair<Uuid, String>>) {
        for ((id, name) in rows) {
            db.execSQL("INSERT OR IGNORE INTO $table (id, name) VALUES (?, ?)", arrayOf(id.toString(), name))
        }
    }
}

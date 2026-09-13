package com.kert0n.medapp.storage.database

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Поток проекции: база только уведомляет, что [tables] изменились, а значение читается заново
 * одной транзакцией ([read]). `combine` независимых потоков дал бы комбинацию, которой в базе не
 * было, а поток DAO видит только таблицы своего запроса и читает словарь уже вне своей транзакции.
 *
 * Таблицы словаря добавляются всегда: проекции держат единицы и формы объектами словаря, и
 * переименованная единица — новость для экрана, даже когда ни одна строка не менялась (PLAN H1).
 */
internal fun <T> MedAppDatabase.observing(vararg tables: String, read: suspend () -> T): Flow<T> =
    invalidationTracker.createFlow(*tables, *VOCABULARY_TABLES).map { withTransaction { read() } }

/** Таблицы словаря, по которым проекции собирают единицы и формы. */
private val VOCABULARY_TABLES = arrayOf("quantity_units", "form_types")

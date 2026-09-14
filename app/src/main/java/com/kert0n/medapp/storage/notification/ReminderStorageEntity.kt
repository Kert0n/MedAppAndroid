package com.kert0n.medapp.storage.notification

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Обязательство сказать человеку, как оно лежит в базе (PLAN D8, F1). Заменяет журнал показов:
 * `shown_at` и есть журнал, а `state` отвечает на то, чего журнал не знал, — что ещё должны
 * сказать и что показано без повода.
 *
 * Ключ приходит строкой `вид:предмет`: в тождество входит и вид, и дата события, поэтому
 * исправленный срок годности или сдвинувшаяся нехватка — другое обязательство, а не то же самое.
 * Цель разложена колонками: в маршруты и `PendingIntent` едут только идентификаторы и дата (G3).
 */
@Entity(
    tableName = "reminders",
    indices = [Index("due_at"), Index("state"), Index("shown_at"), Index("delivery")]
)
class ReminderStorageEntity(
    @PrimaryKey val key: String,
    val kind: String,
    /**
     * Системой или баннером внутри приложения. Выведено из вида при записи, но лежит колонкой:
     * владелец доставки спрашивает «что наступило **системного**», и спрашивать это надо запросом,
     * а не перебором в памяти.
     */
    val delivery: String,
    val subject: String,
    @ColumnInfo(name = "target_kind") val targetKind: String,
    @ColumnInfo(name = "target_id") val targetId: Uuid?,
    @ColumnInfo(name = "target_date") val targetDate: LocalDate?,
    @ColumnInfo(name = "due_at") val dueAt: Instant,
    val state: String,
    @ColumnInfo(name = "shown_at") val shownAt: Instant?
)

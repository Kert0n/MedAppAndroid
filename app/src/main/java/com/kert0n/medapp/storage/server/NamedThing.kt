package com.kert0n.medapp.storage.server

import androidx.room.ColumnInfo
import kotlin.uuid.Uuid

/**
 * Тождество и имя — узкий ответ на вопрос «как это называется». Строка очереди несёт только
 * тождество вещи, а человеку показывают её имя (PLAN H3 №28); читать ради имени всю коробку
 * незачем.
 */
data class NamedThing(@ColumnInfo(name = "id") val id: Uuid, @ColumnInfo(name = "name") val name: String)

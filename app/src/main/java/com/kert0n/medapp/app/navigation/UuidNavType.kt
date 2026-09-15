package com.kert0n.medapp.app.navigation

import android.os.Bundle
import androidx.navigation.NavType
import kotlin.uuid.Uuid

/**
 * Как идентификатор едет в маршруте. Сериализация `Uuid` у `kotlinx.serialization` есть, но
 * навигации нужно ещё и это: чем аргумент кладётся в состояние и как читается обратно.
 *
 * Заведён один раз на всё приложение: маршрутов с идентификаторами много, и второй такой же
 * разъехался бы с первым.
 */
object UuidNavType : NavType<Uuid>(isNullableAllowed = false) {

    override fun put(bundle: Bundle, key: String, value: Uuid) {
        bundle.putString(key, value.toString())
    }

    override fun get(bundle: Bundle, key: String): Uuid? = bundle.getString(key)?.let(Uuid::parse)

    override fun parseValue(value: String): Uuid = Uuid.parse(value)

    override fun serializeAsValue(value: Uuid): String = value.toString()
}

/**
 * Тот же идентификатор, которого может не быть вовсе. Отдельный тип, а не флаг у общего: у формы
 * `null` значит «заводим новое», и это не то же самое, что «непонятно что».
 *
 * Навигация отсутствующий аргумент передаёт словом `null`, а не пустой строкой.
 */
object UuidOrNoneNavType : NavType<Uuid?>(isNullableAllowed = true) {

    override fun put(bundle: Bundle, key: String, value: Uuid?) {
        bundle.putString(key, value?.toString())
    }

    override fun get(bundle: Bundle, key: String): Uuid? = bundle.getString(key)?.let(Uuid::parse)

    override fun parseValue(value: String): Uuid? =
        if (value == "null" || value.isEmpty()) null else Uuid.parse(value)

    override fun serializeAsValue(value: Uuid?): String = value?.toString() ?: "null"
}

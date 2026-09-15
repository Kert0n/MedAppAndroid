package com.kert0n.medapp.app.navigation

import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Куда человек может попасть. Маршрут — величина: равенство по содержимому и есть «то же самое
 * место», и по нему навигация решает, возвращаться назад или вставать поверх.
 *
 * **В маршруте едут только идентификаторы, дата и режим** — ни объектов, ни ключей приглашения
 * (PLAN H3, G3). Маршрут переживает смерть процесса: он сериализуется в аргументы назначения, и
 * объект, положенный в него, к моменту восстановления был бы устаревшей копией того, что лежит в
 * базе. Ключ приглашения там же оказался бы в логах навигации.
 *
 * Пять мест — нижняя навигация; экраны вглубь носят идентификаторы. Идентификатор едет
 * [UuidNavType], а «его может не быть» — [UuidOrNoneNavType]: у формы `null` значит «заводим
 * новое», и это не то же самое, что «непонятно что».
 */
sealed interface Route {

    @Serializable
    data object MedKits : Route

    @Serializable
    data object Plan : Route

    @Serializable
    data object Scanner : Route

    @Serializable
    data object Analytics : Route

    @Serializable
    data object Settings : Route

    /** Создание полки — [medKitId] `null`; правка — её тождество (H3 №3). */
    @Serializable
    data class MedKitForm(val medKitId: Uuid? = null) : Route

    /** Содержимое одной полки (H3 №4). */
    @Serializable
    data class MedKitContents(val medKitId: Uuid) : Route

    /** Все лекарства по всем доступным полкам — тот же экран без области (H3 №5). */
    @Serializable
    data object AllPackages : Route

    /** Карточка упаковки (H3 №6). */
    @Serializable
    data class Package(val packageId: Uuid) : Route

    /**
     * Заведение упаковки в названной полке (H3 №7) либо правка существующей (H3 №8). Два экрана
     * одного маршрута: у них одна форма и одни правила разбора, а различает их то, есть ли уже
     * коробка.
     */
    @Serializable
    data class PackageForm(val medKitId: Uuid? = null, val packageId: Uuid? = null) : Route

    /** Пересчёт и утилизация (H3 №9). */
    @Serializable
    data class PackageAmount(val packageId: Uuid) : Route

    /** Перенос упаковки в другую полку (H3 №11). */
    @Serializable
    data class PackageTransfer(val packageId: Uuid) : Route
}

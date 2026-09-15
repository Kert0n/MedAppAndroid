package com.kert0n.medapp.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Куда человек может попасть. Ключ — **величина**: равенство по содержимому и есть «то же
 * место», и по нему навигация решает, вернуться или встать поверх.
 *
 * **В ключе едут только идентификаторы, дата и режим** — ни объектов, ни ключей приглашения
 * (PLAN H3, G3). Ключ переживает смерть процесса: он сериализуется в сохранённую стопку, и
 * объект, положенный в него, к моменту восстановления был бы устаревшей копией того, что лежит
 * в базе, а ключ приглашения оказался бы в журнале навигации.
 *
 * Аргумент экрана приходит **этим ключом**, а не вычитывается из `SavedStateHandle`: `entry`
 * отдаёт его как есть, и `ViewModel` получает его ассистированным внедрением.
 *
 * Пять мест — нижняя навигация; экраны вглубь добавляются своими PR и носят идентификаторы.
 */
@Serializable
sealed interface Screen : NavKey {

    @Serializable
    data object MedKits : Screen

    @Serializable
    data object Plan : Screen

    @Serializable
    data object Scanner : Screen

    @Serializable
    data object Reports : Screen

    @Serializable
    data object Options : Screen

    /** Создание аптечки — без идентификатора, правка — с ним: это один экран (PLAN H3 №3). */
    @Serializable
    data class MedKitForm(val medKitId: Uuid? = null) : Screen
}

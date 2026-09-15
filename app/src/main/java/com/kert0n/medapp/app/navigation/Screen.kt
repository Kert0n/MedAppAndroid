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

    /**
     * Содержимое полки (№4) и все лекарства сразу (№5) — **один экран с разной областью**:
     * [medKitId] назван — смотрим полку, не назван — все аптечки. Двух ключей здесь нет,
     * потому что нет и двух экранов.
     */
    @Serializable
    data class MedKitContents(val medKitId: Uuid? = null) : Screen

    /**
     * Заведение и правка упаковки — тоже один экран (PLAN H3 №7, №8): [packageId] назван —
     * правка, не назван — заведение, и тогда [medKitId] говорит, откуда человек пришёл.
     */
    @Serializable
    data class PackageForm(val medKitId: Uuid? = null, val packageId: Uuid? = null) : Screen

    /** Карточка упаковки (PLAN H3 №6): сколько есть, что это, где лежит — и что с ней сделать. */
    @Serializable
    data class PackageCard(val packageId: Uuid) : Screen

    /** Пересчёт (PLAN H3 №9): одно число, которое человек увидел в коробке. */
    @Serializable
    data class PackageRecount(val packageId: Uuid) : Screen
}

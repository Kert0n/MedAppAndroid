package com.kert0n.medapp.queue

import com.kert0n.medapp.network.pack.PackageSnapshot
import kotlin.uuid.Uuid

/**
 * Снимок сервера, разрешённый в домен и сверенный с тем, что сервер знал о нас к началу чтения
 * (PLAN E4).
 *
 * Сила снимка в том, что он утверждает не «вот что изменилось», а **«вот всё, что мне доступно»**:
 * только так и можно узнать, что коробку забрали или из полки вывели — дешёвой сверки не
 * существует (B6). Отсюда и [goneMedKits] с [gonePackages]: чего в снимке нет, к тому доступа
 * больше нет.
 *
 * Сверяется это с тем, что сервер знал **до** чтения, а не после: пока снимок летел, человек мог
 * завести коробку, и её отсутствие в ответе ничего не значит. По той же причине из сверки выпадают
 * вещи с непринятым решением — их отсутствие объяснит ответ на их же команду, а не снимок.
 */
class ServerSnapshot(
    val participants: Map<Uuid, Long>,
    packages: List<PackageSnapshot>,
    goneMedKits: Set<Uuid>,
    gonePackages: Set<Uuid>
) {
    /** Свои копии: списки, оставшиеся у вызывающего, меняли бы уже прочитанное. */
    val packages: List<PackageSnapshot> = packages.toList()
    val goneMedKits: Set<Uuid> = goneMedKits.toSet()
    val gonePackages: Set<Uuid> = gonePackages.toSet()
}

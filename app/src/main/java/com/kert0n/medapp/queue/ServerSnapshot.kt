package com.kert0n.medapp.queue

import com.kert0n.medapp.queue.pack.PackageSnapshot
import kotlin.uuid.Uuid

/**
 * Снимок сервера, разрешённый в домен и сверенный с тем, что было у нас к началу чтения
 * (PLAN E4).
 *
 * Сила снимка в том, что он утверждает не «вот что изменилось», а **«вот всё, что мне доступно»**:
 * только так и можно узнать, что коробку забрали, из полки вывели или в полку позвали, — дешёвой
 * сверки не существует (B6). Отсюда [arrivedMedKits] с одной стороны и [goneMedKits] с
 * [gonePackages] — с другой.
 *
 * Сверяется это с тем, что было **до** чтения, а не после: пока снимок летел, человек мог завести
 * коробку или убрать полку, и ответ, прочитанный раньше, об этом не знает. Поэтому [heldPackages]
 * едут вместе со снимком: коробку, которая была и которой к укладке не стало, убрали у нас, и
 * снимок её не возвращает (PLAN C0).
 */
class ServerSnapshot(
    val participants: Map<Uuid, Long>,
    packages: List<PackageSnapshot>,
    goneMedKits: Set<Uuid>,
    gonePackages: Set<Uuid>,
    arrivedMedKits: Set<Uuid>,
    heldPackages: Set<Uuid>
) {
    init {
        require(participants.keys.containsAll(arrivedMedKits)) { "появившуюся полку снимок и называет" }
    }

    /** Свои копии: списки, оставшиеся у вызывающего, меняли бы уже прочитанное. */
    val packages: List<PackageSnapshot> = packages.toList()
    val goneMedKits: Set<Uuid> = goneMedKits.toSet()
    val gonePackages: Set<Uuid> = gonePackages.toSet()
    val arrivedMedKits: Set<Uuid> = arrivedMedKits.toSet()
    val heldPackages: Set<Uuid> = heldPackages.toSet()
}

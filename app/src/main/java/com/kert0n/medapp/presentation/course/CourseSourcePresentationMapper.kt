package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Doses
import com.kert0n.medapp.presentation.value.toPresentationDTO

/**
 * Строка источника из того, что прочитано: состав лечения знает коробку ссылкой, а где она
 * лежит и сколько в ней осталось — проекция коробки; сколько приёмов из неё обеспечено —
 * строка обеспечения.
 *
 * [pack] может не быть: коробка кончилась, и лечение вот-вот потеряет её своим переходом (D5).
 * Тогда строка говорит то, что знает сама, — имя и выделение.
 */
fun CourseSource.toPresentationDTO(
    dose: Dose?,
    pack: PackageProjection?,
    medKitName: String?,
    covered: CourseCoverage.Source?
): CourseSourcePresentationDTO = CourseSourcePresentationDTO(
    packageId = pkg.id,
    name = pkg.name,
    medKitName = medKitName,
    expiresOn = pack?.facts?.expiresOn,
    availableToMe = pack?.availability?.availableToMe?.toPresentationDTO(),
    allocatedDoses = allocatedDoses.count,
    allocatedAmount = dose?.times(allocatedDoses)?.toPresentationDTO(),
    coveredDoses = covered?.coveredDoses?.count,
    maxDoses = (covered?.maxDoses ?: dose?.let { pack?.gives(it, fault) })?.count,
    fault = fault
)

/**
 * Сколько целых доз даёт коробка — потолок ползунка там, где обеспечения нет (PLAN H3 №16):
 * у черновика оно не считается, а «не больше потребности» держит зажим при начале лечения.
 * Отключённому источнику коробка не даёт ничего: он не в той единице, чтобы считать в нём дозы.
 */
private fun PackageProjection.gives(dose: Dose, fault: CourseSource.Fault?): Doses? {
    if (fault != null || availability.availableToMe.unit != dose.unit) return null
    return availability.availableToMe.dosesIn(dose)
}

/** Коробка в выборе источника: чем человек её узнаёт, сколько в ней свободно и можно ли её взять. */
fun PackageProjection.toAttachmentPresentationDTO(
    medKitName: String?,
    attachability: Attachability
): PackageAttachmentPresentationDTO = PackageAttachmentPresentationDTO(
    packageId = id,
    name = facts.name,
    medKitName = medKitName,
    availableToMe = availability.availableToMe.toPresentationDTO(),
    attachability = attachability
)

/** Обеспечение словами экрана: числа приёмов и дни в зоне курса (PLAN D5). */
fun CourseCoverage.toPresentationDTO(): CourseCoveragePresentationDTO = CourseCoveragePresentationDTO(
    requiredDoses = requiredDoses.count,
    coveredDoses = coveredDoses.count,
    missingDoses = missingDoses.count,
    coveredUntilOn = coveredUntil?.atZone(zone)?.toLocalDate(),
    firstUncoveredOn = firstUncoveredAt?.atZone(zone)?.toLocalDate()
)

package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseCoverage
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.domain.value.Dose
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
    maxDoses = covered?.maxDoses?.count,
    fault = fault
)

/** Коробка в выборе источника: то, чем человек её узнаёт, и сколько в ней свободно ему. */
fun PackageProjection.toAttachmentPresentationDTO(medKitName: String?): PackageAttachmentPresentationDTO =
    PackageAttachmentPresentationDTO(
        packageId = id,
        name = facts.name,
        medKitName = medKitName,
        availableToMe = availability.availableToMe.toPresentationDTO()
    )

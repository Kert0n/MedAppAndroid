package com.kert0n.medapp.fixture

import com.kert0n.medapp.domain.intake.CourseIntake
import com.kert0n.medapp.domain.intake.Intake
import com.kert0n.medapp.domain.medkit.MedKitRef
import com.kert0n.medapp.domain.pack.Package
import com.kert0n.medapp.domain.pack.PackageRef
import com.kert0n.medapp.queue.intake.IntakeSyncState
import com.kert0n.medapp.queue.pack.PackageSyncState
import com.kert0n.medapp.storage.intake.IntakeStorageRow
import com.kert0n.medapp.storage.intake.toStorageEntity
import com.kert0n.medapp.storage.medkit.MedKitStorageEntity
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import com.kert0n.medapp.storage.pack.PackageRefStorageRow
import com.kert0n.medapp.storage.pack.PackageStorageRow
import com.kert0n.medapp.storage.pack.toDetailsStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity
import com.kert0n.medapp.storage.pack.toStorageEntity as toClaimsStorageEntity

/**
 * Строки хранения, собранные из доменных объектов так, как их собрала бы база: связи заполнены
 * теми же пачками и аптечками, что вошли в объект. Круговой тест маппера без базы.
 */
fun Package.toStorageRow(sync: PackageSyncState = PackageSyncState(id)): PackageStorageRow =
    PackageStorageRow(
        pack = toStorageEntity(sync),
        record = record.toStorageEntity(),
        details = toDetailsStorageEntity(),
        claims = claims?.toClaimsStorageEntity(id),
        medKit = medKit.row()
    )

/** Строка аптечки по ссылке: имя и место у ссылки не спрашивают, их даёт фикстура. */
fun MedKitRef.row(): MedKitStorageEntity = medKit(id = id, publication = publication).toMedKitStorageEntity()

/** Строка ссылки: запись о коробке с тем, что ссылка о ней знает; момент появления — фикстурный. */
fun PackageRef.toStorageRow(): PackageRefStorageRow = PackageRefStorageRow(
    record = pack(
        id = id,
        name = name,
        quantity = com.kert0n.medapp.domain.value.Quantity(java.math.BigDecimal("20"), unit),
        form = form
    ).record.toStorageEntity()
)

fun Intake.toStorageRow(sync: IntakeSyncState = IntakeSyncState(id)): IntakeStorageRow =
    IntakeStorageRow(
        intake = toStorageEntity(sync),
        planned = (this as? CourseIntake)?.plannedPackage?.toStorageRow(),
        taken = taken?.pkg?.toStorageRow()
    )

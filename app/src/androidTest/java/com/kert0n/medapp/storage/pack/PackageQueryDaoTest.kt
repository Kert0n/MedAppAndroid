package com.kert0n.medapp.storage.pack

import com.kert0n.medapp.fixture.COURSE
import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.MILLILITRES
import com.kert0n.medapp.fixture.SHARED_KIT
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.activeCourse
import com.kert0n.medapp.fixture.expiry
import com.kert0n.medapp.fixture.inMemoryDatabase
import com.kert0n.medapp.fixture.save
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.millilitres
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.source
import com.kert0n.medapp.fixture.tablets
import com.kert0n.medapp.storage.course.ActivePackageAssignmentStorageEntity
import com.kert0n.medapp.storage.course.toStorageEntity as toCourseStorageEntity
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.medkit.toStorageEntity as toMedKitStorageEntity
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import com.kert0n.medapp.fixture.VOCABULARY
import com.kert0n.medapp.fixture.TABLET_FORM_ID

/**
 * Один запрос отвечает на поиск, фильтр и сортировку сразу, и просроченные идут первыми при
 * любой сортировке (PLAN H4, F1).
 */
class PackageQueryDaoTest {

    private lateinit var database: MedAppDatabase
    private val packages get() = database.packages()

    private val today = LocalDate.of(2027, 3, 1)

    private fun id(last: Int): Uuid =
        Uuid.parse("00000000-0000-4000-8000-0000000001%02d".format(last))

    private val paracetamol = pack(
        id = id(1), name = "Парацетамол", quantity = tablets("20"),
        form = TABLET_FORM, category = "Обезболивающие",
        expiresOn = expiry("2027-12-31")
    )
    private val ibuprofen = pack(
        id = id(2), name = "Ибупрофен", quantity = tablets("5"),
        form = TABLET_FORM, category = "Обезболивающие",
        expiresOn = expiry("2027-04-10")
    )
    private val expired = pack(
        id = id(3), name = "Янтарная кислота", quantity = tablets("100"),
        category = "Витамины", expiresOn = expiry("2027-02-01")
    )
    private val syrup = pack(
        id = id(4), medKit = medKit(id = SHARED_KIT, name = "Дача").ref, name = "Сироп", quantity = millilitres("200")
    )

    private val all = listOf(paracetamol, ibuprofen, expired, syrup)

    @Before
    fun openDatabase() = runTest {
        database = inMemoryDatabase()
        database.medKits().upsert(medKit(id = HOME_KIT).toMedKitStorageEntity())
        database.medKits().upsert(medKit(id = SHARED_KIT, name = "Дача").toMedKitStorageEntity())
        all.forEachIndexed { index, pkg ->
            val added = Instant.EPOCH.plusSeconds(index.toLong() * 3600)
            packages.save(
                pack(
                    id = pkg.id, medKit = pkg.medKit, name = pkg.name, quantity = pkg.quantity,
                    form = pkg.facts.form, category = pkg.facts.category,
                    expiresOn = pkg.facts.expiresOn, addedAt = added
                )
            )
        }
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun names(query: PackageQuery): List<String> =
        packages.matching(query, today).map { it.toDomain(VOCABULARY).name }

    @Test
    fun withoutFilterEverythingIsThereWithExpiredFirst() = runTest {
        assertEquals(
            listOf("Янтарная кислота", "Ибупрофен", "Парацетамол", "Сироп"),
            names(PackageQuery())
        )
    }

    @Test
    fun oneMedKitAtATime() = runTest {
        assertEquals(listOf("Сироп"), names(PackageQuery(medKitId = SHARED_KIT)))
        assertEquals(3, names(PackageQuery(medKitId = HOME_KIT)).size)
    }

    /** Поиск без учёта регистра работает и по-русски: `lower()` в SQLite знает только латиницу. */
    @Test
    fun searchIgnoresCaseInCyrillicToo() = runTest {
        assertEquals(listOf("Парацетамол"), names(PackageQuery(text = "парацет")))
        assertEquals(listOf("Парацетамол"), names(PackageQuery(text = "  ПАРАЦЕТ ")))
        assertEquals(emptyList<String>(), names(PackageQuery(text = "аспирин")))
    }

    @Test
    fun everyFilterSelectsItsOwn() = runTest {
        assertEquals(
            listOf("Янтарная кислота"),
            names(PackageQuery(filter = PackageQuery.Filter.Expired))
        )
        assertEquals(
            listOf("Ибупрофен"),
            names(PackageQuery(filter = PackageQuery.Filter.ExpiringWithin(60)))
        )
        assertEquals(
            listOf("Ибупрофен", "Парацетамол"),
            names(PackageQuery(filter = PackageQuery.Filter.OfCategory("Обезболивающие")))
        )
        assertEquals(
            listOf("Ибупрофен", "Парацетамол"),
            names(PackageQuery(filter = PackageQuery.Filter.OfForm(TABLET_FORM_ID)))
        )
    }

    @Test
    fun onCourseSelectsAssignedPackagesOnly() = runTest {
        database.courses().upsertCourse(
            activeCourse(sources = listOf(source(id(1), 5))).toCourseStorageEntity()
        )
        database.courses().assignPackage(ActivePackageAssignmentStorageEntity(id(1), COURSE))

        assertEquals(
            listOf("Парацетамол"),
            names(PackageQuery(filter = PackageQuery.Filter.OnCourse))
        )
    }

    /** «Свободно» запросом не выражается: фильтр не сужает выборку, его накладывают поверх. */
    @Test
    fun hasFreeIsLeftToTheLayerAboveTheQuery() = runTest {
        assertEquals(names(PackageQuery()), names(PackageQuery(filter = PackageQuery.Filter.HasFree)))
    }

    @Test
    fun expiredComeFirstUnderEverySort() = runTest {
        for (sort in PackageQuery.Sort.entries) {
            assertEquals(
                "сортировка $sort",
                "Янтарная кислота",
                names(PackageQuery(sort = sort)).first()
            )
        }
    }

    @Test
    fun eachSortOrdersTheRest() = runTest {
        assertEquals(
            listOf("Ибупрофен", "Парацетамол", "Сироп"),
            names(PackageQuery(sort = PackageQuery.Sort.NAME)).drop(1)
        )
        assertEquals(
            listOf("Ибупрофен", "Парацетамол", "Сироп"),
            names(PackageQuery(sort = PackageQuery.Sort.EXPIRY)).drop(1)
        )
        assertEquals(
            listOf("Сироп", "Ибупрофен", "Парацетамол"),
            names(PackageQuery(sort = PackageQuery.Sort.ADDED_AT)).drop(1)
        )
    }

    /** Порядок по количеству — числовой, а не текстовый: «10» выше «2», и единицы не смешиваются. */
    @Test
    fun quantityOrderIsNumericWithinAUnit() = runTest {
        val ten = pack(id = id(5), name = "Десять", quantity = tablets("10"))
        val two = pack(id = id(6), name = "Два", quantity = tablets("2"))
        for (pkg in listOf(ten, two)) {
            packages.save(pkg)
        }

        val ordered = names(PackageQuery(medKitId = HOME_KIT, sort = PackageQuery.Sort.QUANTITY))
        assertEquals(
            listOf("Янтарная кислота", "Два", "Ибупрофен", "Десять", "Парацетамол"),
            ordered
        )
    }

    @Test
    fun quantitySortKeepsUnitsApart() = runTest {
        val ordered = packages.matching(
            PackageQuery(sort = PackageQuery.Sort.QUANTITY),
            today
        ).drop(1).map { it.pack.quantityUnitId }
        assertEquals(1, ordered.count { it == MILLILITRES.id })
    }

    @Test
    fun searchAndFilterAndSortWorkTogether() = runTest {
        assertEquals(
            listOf("Ибупрофен"),
            names(
                PackageQuery(
                    medKitId = HOME_KIT,
                    text = "и",
                    filter = PackageQuery.Filter.OfCategory("Обезболивающие"),
                    sort = PackageQuery.Sort.QUANTITY
                )
            )
        )
    }
}

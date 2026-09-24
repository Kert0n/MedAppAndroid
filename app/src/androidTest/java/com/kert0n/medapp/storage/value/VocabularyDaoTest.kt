package com.kert0n.medapp.storage.value

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.value.DosageForm
import com.kert0n.medapp.domain.value.QuantityUnit
import com.kert0n.medapp.network.value.bundledVocabulary
import com.kert0n.medapp.storage.database.BundledVocabulary
import com.kert0n.medapp.storage.database.MedAppDatabase
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Словари есть сразу после создания базы, а обновление с сервера переименовывает и добавляет
 * записи, ничего не удаляя: на снятую единицу ещё могут ссылаться пачки.
 */
class VocabularyDaoTest {

    private val tablets = Uuid.parse("00000000-0000-4000-8000-000000000031")
    private val milligrams = Uuid.parse("00000000-0000-4000-8000-000000000032")
    private val pills = Uuid.parse("00000000-0000-4000-8000-000000000041")

    private val snapshot = """
        {"origin":"https://medapp.test","capturedOn":"2026-09-10","version":1,
         "quantityUnits":[{"id":"$tablets","name":"таб"},{"id":"$milligrams","name":"мг"}],
         "formTypes":[{"id":"$pills","name":"таблетки"}]}
    """

    private lateinit var database: MedAppDatabase
    private lateinit var repository: VocabularyRoomRepository

    @Before
    fun openDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MedAppDatabase::class.java)
            .addCallback(BundledVocabulary { bundledVocabulary(snapshot) })
            .build()
        repository = VocabularyRoomRepository(database.vocabulary())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun freshDatabaseAlreadyHasTheBundledVocabulary() = runTest {
        assertEquals(listOf("мг", "таб"), repository.observeUnits().first().map { it.name })
        assertEquals(listOf(DosageForm(pills, "таблетки")), repository.observeForms().first())
    }

    @Test
    fun refreshRenamesInPlaceWithoutDuplicates() = runTest {
        repository.save(listOf(QuantityUnit(tablets, "таблетки")), emptyList())

        val units = repository.observeUnits().first()
        assertEquals(2, units.size)
        assertEquals("таблетки", units.single { it.id == tablets }.name)
    }

    @Test
    fun refreshAddsWhatTheServerAdded() = runTest {
        val drops = DosageForm(Uuid.parse("00000000-0000-4000-8000-000000000042"), "капли")

        repository.save(emptyList(), listOf(drops))

        assertEquals(listOf(drops, DosageForm(pills, "таблетки")), repository.observeForms().first())
    }

    @Test
    fun refreshNeverRemovesWhatPackagesMayReference() = runTest {
        repository.save(emptyList(), emptyList())

        assertEquals(2, repository.observeUnits().first().size)
        assertEquals(1, repository.observeForms().first().size)
    }
}

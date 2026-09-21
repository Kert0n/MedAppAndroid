package com.kert0n.medapp.tour

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.domain.value.Dose
import com.kert0n.medapp.domain.value.Quantity
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.packageRepository
import com.kert0n.medapp.storage.database.MedAppDatabase
import com.kert0n.medapp.storage.database.BundledVocabulary
import java.math.BigDecimal
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Посев мира в настоящее приложение** — для показа, который снимают руками с телефона
 * (`docs/demo/съёмка-с-телефона.md`). Камера там живая, шторка настоящая, а состояния, которых за
 * две минуты перед камерой не создать — просроченная пачка, чужие брони, изменение в пути, отказ
 * сервера по версии, лечение с нехваткой, — уже лежат в базе.
 *
 * Пишет он **тот самый файл базы**, который открывает приложение: Room строится по имени
 * [MedAppDatabase.NAME] на контексте приложения, а не на подделке проверки. Поэтому посев
 * включается только явным `-e stage true` — в обычном прогоне ему нечего делать, и трогать чужую
 * базу он не должен.
 *
 * Связь на время посева и съёмки **отнимают**: мир выдуман, и очередь, увидев сеть, повезёт его
 * боевому серверу.
 */
@RunWith(AndroidJUnit4::class)
class PhoneStage {

    /**
     * Сколько минут у снимающего до того, как придёт напоминание: приём ставится на это время
     * вперёд, чтобы шторку можно было снять по ходу сюжета, а не гнаться за ней.
     */
    private val dueInMinutes: Long
        get() = InstrumentationRegistry.getArguments().getString("due")?.toLongOrNull() ?: 6

    @Test
    fun stage() {
        assumeTrue(
            "посев в настоящее приложение включается только -e stage true",
            InstrumentationRegistry.getArguments().getString("stage") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, MedAppDatabase::class.java, MedAppDatabase.NAME)
            .addCallback(BundledVocabulary.fromAssets(context.assets))
            .addMigrations(*MedAppDatabase.MIGRATIONS)
            .build()
        try {
            val world = TourWorld(database)
            runBlocking {
                world.seed()
                world.intakeDueNow(dueInMinutes)
                // Вторая пачка того же лекарства: у неё срок ближе, и на записи видно, зачем
                // человек меняет порядок расходования. Одной пачкой приоритет не показать.
                database.packageRepository().add(
                    pack(
                        id = Uuid.random(), name = "Нурофен Экспресс", medKit = world.home.ref,
                        quantity = Quantity(BigDecimal("10"), world.pieces),
                        form = world.tabletsForm, category = "Обезболивающие",
                        expiresOn = ExpiryDate(world.today.plusMonths(2)),
                        defaultIntakeAmount = Dose(Quantity(BigDecimal("1"), world.pieces)),
                        addedAt = world.now
                    )
                )
            }
        } finally {
            database.close()
        }
    }
}

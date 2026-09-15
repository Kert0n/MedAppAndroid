package com.kert0n.medapp.app.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Где человек стоит, видно **формой значка**, а не одним цветом (PLAN H3): у выбранного места
 * значок залит.
 *
 * Проверяется нарисованное, а не объявленное: объявить второй значок мало — у геометрических
 * узоров залитого варианта не существует, и `qr_code_scanner-fill` в наборе Material Symbols
 * побайтно совпадает с обычным. Файлы-двойники так и остались бы «как у всех» в коде и
 * невыбранными на экране.
 *
 * Сканер — **названное исключение**, и оно временное: заменить QR-узор нечем без выбора значка,
 * а выбор вынесен в issue #34. Исключение здесь именно названо, а не обойдено молчанием: когда
 * значок сменят, [theScannerIsTheOnlyPlaceWithoutAFilledIcon] покраснеет и напомнит убрать эту
 * строку.
 *
 * Красная проверка: снять залитый значок у любого другого места — проверка назовёт его.
 */
@RunWith(AndroidJUnit4::class)
class DestinationIconsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSelectedPlaceLooksDifferentFromTheUnselectedOne() {
        for (destination in Destination.entries - EXCEPTION) {
            val name = context.resources.getResourceEntryName(destination.icon)

            assertTrue(
                "у места $name нет залитого значка: выбранность несёт один цвет",
                destination.iconSelected != null
            )
            assertTrue(
                "у места $name залитый значок совпадает с обычным: выбранность не видна формой",
                !destination.icon(selected = true).drawn().sameAs(destination.icon(false).drawn())
            )
        }
    }

    /** Исключение ровно одно, и оно то самое: второе сюда не проскочит. */
    @Test
    fun theScannerIsTheOnlyPlaceWithoutAFilledIcon() {
        val unfilled = Destination.entries.filter { it.iconSelected == null }

        assertEquals(listOf(EXCEPTION), unfilled)
    }

    /** Нарисованный значок: сравнивать имена файлов мало — совпасть могут и разные файлы. */
    private fun Int.drawn(): Bitmap {
        val drawable = ContextCompat.getDrawable(context, this)
        assertNotNull("значка $this нет", drawable)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawable!!.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        val EXCEPTION = Destination.SCANNER
        const val SIZE = 48
    }
}

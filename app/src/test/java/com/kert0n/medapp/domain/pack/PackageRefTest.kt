package com.kert0n.medapp.domain.pack

import com.kert0n.medapp.fixture.HOME_KIT
import com.kert0n.medapp.fixture.PACK
import com.kert0n.medapp.fixture.TABLETS
import com.kert0n.medapp.fixture.TABLET_FORM
import com.kert0n.medapp.fixture.medKit
import com.kert0n.medapp.fixture.pack
import com.kert0n.medapp.fixture.withShared
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ссылка на пачку — то, что о ней знает чужой агрегат: тождество, имя, единица, форма. Ни
 * остатка, ни места, ни переходов: списать из курса нечем, а есть ли коробка сейчас, знает
 * только живая пачка (PLAN C1, D3).
 */
class PackageRefTest {

    @Test
    fun refCarriesWhatACourseNeedsToKnow() {
        val ref = pack(form = TABLET_FORM, medKit = medKit(id = HOME_KIT).ref).ref
        assertEquals(PACK, ref.id)
        assertEquals("Парацетамол", ref.name)
        assertEquals(TABLETS, ref.unit)
        assertEquals(TABLET_FORM, ref.form)
    }

    @Test
    fun refPointsAtThePackNotAtItsState() {
        // Та же пачка с новым именем — та же ссылка: источник курса её не потеряет.
        val before = pack().ref
        val renamed = pack().describe(pack().facts.withShared(name = "Панадол")).ref
        assertEquals(before, renamed)
        assertEquals(before.hashCode(), renamed.hashCode())
        assertNotEquals(before, pack(id = com.kert0n.medapp.fixture.OTHER_PACK).ref)
    }
}

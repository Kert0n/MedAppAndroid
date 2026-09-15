package com.kert0n.medapp.presentation.course

import com.kert0n.medapp.domain.course.CourseRejected
import com.kert0n.medapp.domain.course.CourseSource
import com.kert0n.medapp.domain.pack.ExpiryDate
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Источник лечения строкой экрана источников (PLAN H3 №16): что за коробка, где лежит, сколько
 * из неё доступно мне и сколько приёмов из неё выделено. Место в списке — очередь расходования,
 * и держит его сам порядок списка.
 *
 * [coveredDoses] и [maxDoses] приходят из обеспечения, а его у черновика нет (D5): там они
 * пусты, и потолок ползунка считается по тому, сколько даёт коробка. [fault] — источник
 * отключён: выделять ему нечего, и строка это говорит.
 */
data class CourseSourcePresentationDTO(
    val packageId: Uuid,
    val name: String,
    val medKitName: String?,
    val expiresOn: ExpiryDate?,
    val availableToMe: QuantityPresentationDTO?,
    val allocatedDoses: Int,
    /** Выделенное в единицах коробки — «= 10 капс.»: то же число, сказанное привычной мерой. */
    val allocatedAmount: QuantityPresentationDTO?,
    val coveredDoses: Int?,
    val maxDoses: Int?,
    val fault: CourseSource.Fault?
)

/**
 * Коробка, которую можно подключить (PLAN H3 №17): чем она названа, где лежит и сколько в ней
 * свободно.
 */
data class PackageAttachmentPresentationDTO(
    val packageId: Uuid,
    val name: String,
    val medKitName: String?,
    val availableToMe: QuantityPresentationDTO?
)

/**
 * Чем кончилась попытка записать состав — то, что человек прочтёт. Случаи различаются тем, что
 * он делает дальше: занятую коробку освобождает другое лечение, непригодную — не вернуть,
 * отказ назначения чинится назначением, у законченного лечения источников уже нет.
 */
sealed interface CourseSourcesMessage {

    /** Коробку держит другое идущее лечение (PLAN D5 «одна пачка — один активный курс»). */
    data class Taken(val name: String?) : CourseSourcesMessage

    /** Коробку выбрасывают или её полки больше нет. */
    data class Unusable(val name: String?) : CourseSourcesMessage

    data class Refused(val reason: CourseRejected.Reason) : CourseSourcesMessage

    /** Коробке досталось больше, чем она даёт или чем оставляет потребность: строка встаёт на предел. */
    data class BeyondLimit(val name: String?, val limit: Int) : CourseSourcesMessage

    data object Finished : CourseSourcesMessage
}

/**
 * Чем лечение обеспечено (PLAN H3 №14, №16): сколько приёмов ещё нужно, сколько из них покрывают
 * коробки и с какого дня их не хватает. Дни — в зоне курса: у полуночи день устройства бывает уже
 * другим (D5). У черновика обеспечения нет вовсе — считать его не по чему (B15).
 */
data class CourseCoveragePresentationDTO(
    val requiredDoses: Int,
    val coveredDoses: Int,
    val missingDoses: Int,
    val coveredUntilOn: LocalDate?,
    val firstUncoveredOn: LocalDate?
) {
    val isFullyCovered: Boolean get() = missingDoses == 0
}

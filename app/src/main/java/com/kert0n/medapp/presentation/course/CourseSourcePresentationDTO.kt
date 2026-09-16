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
 * Коробка в выборе источника (PLAN H3 №17): чем она названа, где лежит, сколько в ней свободно
 * и **можно ли её подключить**. Неподходящая не прячется: человек ищет именно её и должен
 * прочитать, почему она не идёт.
 */
data class PackageAttachmentPresentationDTO(
    val packageId: Uuid,
    val name: String,
    val medKitName: String?,
    val availableToMe: QuantityPresentationDTO?,
    val attachability: Attachability,
    /**
     * Последний годный день, когда коробка **уже просрочена**. Подключить её можно — это решение
     * человека, — но видно это сразу, а не после первого приёма (PLAN C1 «Просрочка при планировании»).
     */
    val expiredOn: LocalDate? = null
) {
    val isAttachable: Boolean get() = attachability == Attachability.Attachable
}

/**
 * Можно ли подключить коробку к этому лечению, и если нет — почему. Случаи различаются тем, что
 * человек делает дальше: у одной коробки он поправит форму, другую освободит другое лечение,
 * третью не вернуть вовсе. Совместимость решает домен (`CourseSource.Fault.between`), занятость —
 * проекция коробки, пригодность — её статус (PLAN D5).
 */
sealed interface Attachability {

    data object Attachable : Attachability

    /** Уже в составе этого лечения — второй раз её не подключают. */
    data object Attached : Attachability

    /** Держит другое идущее лечение: одна пачка — один активный курс (D5). */
    data class HeldByCourse(val title: String?) : Attachability

    /** У коробки не заполнена форма: сказать, тот ли это препарат, нечем. */
    data object NeedsForm : Attachability

    /** Форма или единица коробки не те, что назначены. */
    data class Mismatch(val fault: CourseSource.Fault) : Attachability

    /** Коробку выбрасывают или её полки больше нет. */
    data object Unusable : Attachability

    /** У лечения ещё не названы доза и форма — сверять коробку не с чем. */
    data object PrescriptionIncomplete : Attachability
}

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

    /** Состав правили с другого экрана: то, что здесь, устарело (PLAN F5). */
    data object Stale : CourseSourcesMessage
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

/**
 * Итог собранного состава (PLAN H3 №16): сколько приёмов нужно, сколько из них покрывают коробки
 * и скольких не хватает. Считается на месте, по составу на экране, — поэтому дня, с которого не
 * хватает, здесь нет: его знает только записанное обеспечение, разложенное по пунктам календаря.
 */
data class CourseEstimatePresentationDTO(
    val requiredDoses: Int,
    val coveredDoses: Int,
    val missingDoses: Int
)

/**
 * Только что подключённая коробка просрочена: об этом говорится **один раз**, сразу после
 * подключения. Не запрет и не вопрос — лечение уже берёт из неё; человек должен это знать
 * (PLAN C1 «Просрочка при планировании», решение владельца 2026-09-16).
 */
data class ExpiredSourcePresentationDTO(val name: String, val expiredOn: LocalDate)

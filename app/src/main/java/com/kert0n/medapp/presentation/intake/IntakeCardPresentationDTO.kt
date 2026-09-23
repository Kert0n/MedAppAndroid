package com.kert0n.medapp.presentation.intake

import com.kert0n.medapp.presentation.value.ExpiryDatePresentationDTO
import com.kert0n.medapp.domain.intake.IntakeRejected
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.QuantityPresentationError
import com.kert0n.medapp.presentation.value.UnitPresentationDTO
import java.time.LocalDate
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * Что человек набрал на карточке пункта (PLAN H3 №18): сколько принял и когда. Единица не
 * набирается — её знает лечение, и другой у его пункта быть не может.
 *
 * Момент стоит в полях **заполненным**: приём чаще всего записывают сразу, и переключателя
 * «сейчас или тогда» для этого не нужно — человек либо не трогает поля, либо называет свой день и
 * час (D6 «принять вчерашнее сегодня законно, пока эпизод открыт»).
 */
data class IntakeCardPresentationDTO(
    val amount: String = "",
    val on: LocalDate? = null,
    val at: LocalTime? = null,
    val packageId: Uuid? = null
)

/**
 * Карточка пункта целиком.
 *
 * [answer] есть у отвеченного пункта: он уже история, и спрашивать по нему нечего — карточка
 * показывает, что записано, и действий не предлагает.
 */
data class IntakeCardUiState(
    val isLoading: Boolean = false,
    val isGone: Boolean = false,
    val title: String = "",
    val plannedOn: LocalDate? = null,
    val plannedAt: LocalTime? = null,
    val packageId: Uuid? = null,
    val packageName: String? = null,
    val sources: List<IntakeSourcePresentationDTO> = emptyList(),
    /** Срок выбранной коробки, если к дню приёма она просрочена. */
    val expired: ExpiryDatePresentationDTO? = null,
    val plannedAmount: QuantityPresentationDTO? = null,
    val unit: UnitPresentationDTO? = null,
    val form: IntakeCardPresentationDTO = IntakeCardPresentationDTO(),
    val answer: Answer? = null,
    val answeredAt: LocalTime? = null,
    val error: IntakeCardError? = null,
    /** Что спросить до записи: ничего не записано, пока человек не ответит (PLAN D6). */
    val questions: List<IntakeQuestionPresentationDTO> = emptyList(),
    val isWriting: Boolean = false,
    val isDone: Boolean = false
) {

    /** Чем пункт кончился, если кончился. */
    enum class Answer { TAKEN, MISSED, CANCELLED }

    /**
     * Можно ли ещё отвечать. Пока идёт запись, второе нажатие ничего не начинает — сторожем служит
     * само состояние. Пропущенный пункт отвечать **можно**: доза уехала вперёд, и подтвердить её
     * позже законно (PLAN D6, решение владельца 2026-09-16); принятый и отменённый — история.
     */
    val canAnswer: Boolean
        get() = !isWriting && !isGone && (answer == null || answer == Answer.MISSED)

    /**
     * Записать приём можно, когда есть откуда: выбор, ушедший из источников, пуст, и кнопка ждёт
     * нового выбора, а не молчит на нажатие (C1 «Действие — по показанному»).
     */
    val canConfirm: Boolean get() = canAnswer && packageId != null

    /** Отказаться можно от того, на что ещё не ответили: пропущенное уже пропущено. */
    val canDecline: Boolean get() = canAnswer && answer == null
}

/**
 * Коробка, из которой можно принять этот пункт: источник лечения. Чужая сюда не попадает — приём
 * из неё пункта не закрывает, это внеплановый факт (PLAN D5).
 */
data class IntakeSourcePresentationDTO(val id: Uuid, val name: String)

/** Вопрос сценария словами экрана: ответ на него — тот же приём, подтверждённый человеком. */
sealed interface IntakeQuestionPresentationDTO {

    /** Коробка [name] просрочена к дню приёма: годна была до [expiry]. */
    data class Expired(val name: String, val expiry: ExpiryDatePresentationDTO) : IntakeQuestionPresentationDTO

    /** Приём заденет выделенное лечению или занятое соседями (PLAN D4). */
    data class TouchesReserved(val free: QuantityPresentationDTO) : IntakeQuestionPresentationDTO
}

/** Чем кончился разбор набранного или сама запись: у каждой беды своё место и свои слова. */
sealed interface IntakeCardError {

    /** Число не разобралось; чем именно — говорит разбор величины. */
    data class Amount(val error: QuantityPresentationError) : IntakeCardError

    /** Сценарий отверг приём: в коробке столько не наберётся, эпизод закрыт, пачка не источник. */
    data class Rejected(val reason: IntakeRejected.Reason) : IntakeCardError

    /** На пункт уже ответили — с другого экрана или из шторки: карточка перечитает. */
    data object AlreadyAnswered : IntakeCardError
}

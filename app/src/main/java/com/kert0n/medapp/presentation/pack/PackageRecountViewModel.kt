package com.kert0n.medapp.presentation.pack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kert0n.medapp.domain.pack.PackageProjection
import com.kert0n.medapp.feature.operation.Freshening
import com.kert0n.medapp.feature.packages.PackageAdjusting
import com.kert0n.medapp.presentation.ParsedInput
import com.kert0n.medapp.presentation.value.QuantityPresentationDTO
import com.kert0n.medapp.presentation.value.toDomain
import com.kert0n.medapp.presentation.value.toPresentationDTO
import com.kert0n.medapp.storage.pack.PackageStorageRepository
import com.kert0n.medapp.storage.value.VocabularyStorageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Пересчёт (PLAN H3 №9): человек посчитал и увидел не то число, что записано. Он называет то,
 * что видит **целиком**, а разницу считает учёт — и отсчитывается она от числа, которое человек
 * видел на экране (C1), поэтому `seen` берётся из той же оценки, что показана, а не дочитывается
 * заново в момент нажатия.
 *
 * «Выбросить» сюда не приходит: выбрасывают пачку с карточки, а не таблетки из учёта. Поэтому и
 * ноль не принимается: ноль — это «выбросить», и пересчётом коробку не кончают.
 *
 * Коробка общей полки при открытии перечитывается, и пока сервер не ответил, экран ждёт: «сейчас
 * записано» должно быть свежим — от него считается разница (PLAN E4).
 */
@HiltViewModel(assistedFactory = PackageRecountViewModel.Factory::class)
class PackageRecountViewModel @AssistedInject constructor(
    private val adjusting: PackageAdjusting,
    freshening: Freshening,
    private val vocabulary: VocabularyStorageRepository,
    packages: PackageStorageRepository,
    @Assisted private val packageId: Uuid
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(packageId: Uuid): PackageRecountViewModel
    }

    private val form = MutableStateFlow(PackageRecountPresentationDTO())

    private val progress = MutableStateFlow(Progress())

    /** Перечитывание коробки при открытии кончилось (PLAN E4). */
    private val freshened = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try {
                freshening.pack(packageId)
            } finally {
                freshened.value = true
            }
        }
    }

    /**
     * Коробка, как её прочитали, — доменной проекцией, а не строками экрана: от увиденного числа
     * отсчитывается разница, и собирать его обратно из строки значило бы разбирать то, что уже
     * было величиной. Прочитана или ещё нет — часть ответа: `null` внутри [Reading.Read] значит
     * «коробки нет», а само отсутствие чтения не значит ничего.
     */
    private val seen = MutableStateFlow<Reading>(Reading.Unread)

    init {
        viewModelScope.launch { packages.observe(packageId).collect { seen.value = Reading.Read(it) } }
    }

    val state: StateFlow<PackageRecountUiState> = combine(seen, form, progress, freshened) { reading, form, progress, freshened ->
        val pack = (reading as? Reading.Read)?.pack
        PackageRecountUiState(
            isFreshening = !freshened,
            pack = pack?.toPresentationDTO(),
            form = form,
            isGone = freshened && reading is Reading.Read && pack == null,
            error = progress.error,
            isDone = progress.done
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageRecountUiState())

    /** Ввод снимает отказ: человек уже правит то, на что ему указали. */
    fun edit(edited: PackageRecountPresentationDTO) {
        form.value = edited
        if (progress.value.error != null) progress.value = Progress()
    }

    /**
     * Записать. Второе нажатие, пока идёт первое, ничего не начинает: замок ставится **до**
     * `launch`, потому что между нажатием и записью стоит чтение словаря, и второе нажатие
     * проходит именно в это окно.
     */
    fun submit() {
        val now = progress.value
        if (now.working || now.done) return
        progress.value = Progress(working = true)
        viewModelScope.launch {
            progress.value = when (val parsed = parse()) {
                is ParsedInput.Rejected -> Progress(error = parsed.error)
                is ParsedInput.Parsed -> when (adjusting.adjust(packageId, parsed.value)) {
                    // Кончиться коробка здесь не может — ноль отвергнут разбором; исход назван
                    // ради полноты, и ведёт он туда же: записано.
                    PackageAdjusting.Outcome.ADJUSTED, PackageAdjusting.Outcome.ENDED -> Progress(done = true)
                    PackageAdjusting.Outcome.GONE -> Progress(error = PackageRecountError.Gone)
                    PackageAdjusting.Outcome.UNUSABLE -> Progress(error = PackageRecountError.Busy)
                }
            }
        }
    }

    /** Что человек назвал, в единице коробки. Нажать до чтения нельзя: экран ещё показывает загрузку. */
    private suspend fun parse(): ParsedInput<PackageAdjusting.Action.Recount, PackageRecountError> {
        val pack = (seen.value as? Reading.Read)?.pack ?: return ParsedInput.Rejected(PackageRecountError.Gone)
        val shown = pack.availability.effective
        val typed = QuantityPresentationDTO(form.value.amount, shown.unit.toPresentationDTO())
        return when (val parsed = typed.toDomain(vocabulary.snapshot())) {
            is ParsedInput.Rejected -> ParsedInput.Rejected(PackageRecountError.Amount(parsed.error))
            is ParsedInput.Parsed ->
                if (parsed.value.isZero) ParsedInput.Rejected(PackageRecountError.Zero)
                else ParsedInput.Parsed(PackageAdjusting.Action.Recount(seen = shown, actual = parsed.value))
        }
    }

    private sealed interface Reading {
        data object Unread : Reading
        data class Read(val pack: PackageProjection?) : Reading
    }

    private data class Progress(
        val working: Boolean = false,
        val error: PackageRecountError? = null,
        val done: Boolean = false
    )
}

/** Что показывает экран пересчёта. */
data class PackageRecountUiState(
    /** Коробка перечитывается у сервера: экран ждёт (PLAN E4). */
    val isFreshening: Boolean = false,
    val pack: PackagePresentationDTO? = null,
    val form: PackageRecountPresentationDTO = PackageRecountPresentationDTO(),
    val isGone: Boolean = false,
    val error: PackageRecountError? = null,
    /** Записано: экран уходит. */
    val isDone: Boolean = false
) {
    val isLoading: Boolean get() = isFreshening || (pack == null && !isGone)
}

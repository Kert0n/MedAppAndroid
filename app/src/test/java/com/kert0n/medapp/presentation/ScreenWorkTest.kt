package com.kert0n.medapp.presentation

import androidx.lifecycle.ViewModel
import com.kert0n.medapp.domain.Unavailability
import com.kert0n.medapp.fixture.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Работа экрана, у сбоя которой есть владелец (ТЗ 4.3): нажатие, чтение потоком и чтение при
 * открытии. По проверке на каждый переход модели — сбой, отмена, повтор, удача.
 */
class ScreenWorkTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private class Screen : ViewModel()

    private fun collected(failures: ScreenFailures): MutableList<Unit> {
        val told = mutableListOf<Unit>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { failures.failures.collect { told += it } }
        return told
    }

    /** Нажатие не сработало — «идёт работа» снято, и об этом сказано. Без этого экран висит, а человек не знает почему. */
    @Test
    fun aFailedPressIsUndoneAndTold() {
        val failures = ScreenFailures()
        val told = collected(failures)
        val busy = MutableStateFlow(true)

        Screen().act(failures, undo = { busy.value = false }) { throw IllegalStateException("database or disk is full") }

        assertEquals(false, busy.value)
        assertEquals(1, told.size)
    }

    /** Ушёл с экрана — работа отменена, и это не сбой: сказать «не получилось» о том, что человек сам оставил, — соврать. */
    @Test
    fun aCancelledPressIsNeitherUndoneNorTold() {
        val failures = ScreenFailures()
        val told = collected(failures)
        var undone = false
        val never = CompletableDeferred<Unit>()

        val job = Screen().act(failures, undo = { undone = true }) { never.await() }
        job.cancel()

        assertEquals(false, undone)
        assertEquals(0, told.size)
    }

    /** Удавшееся нажатие ничего не снимает и ни о чём не говорит. */
    @Test
    fun aPressThatWorksSaysNothing() {
        val failures = ScreenFailures()
        val told = collected(failures)
        var undone = false

        Screen().act(failures, undo = { undone = true }) { }

        assertEquals(false, undone)
        assertEquals(0, told.size)
    }

    /** Поток не прочитался — экран знает причину, а поток не роняет того, кто его слушает. */
    @Test
    fun aFlowThatThrowsBecomesAFailedReading() = runTest {
        val reading = ScreenReading()
        val seen = mutableListOf<Int>()

        // Чтение экрана не кончается само: повтор открывает его заново, поэтому его слушают, а не дочитывают.
        val listening = launch { reading.guarded(flow<Int> { throw IllegalStateException("file is not a database") }).collect { seen += it } }
        testScheduler.runCurrent()

        assertEquals(emptyList<Int>(), seen)
        assertEquals(Unavailability.DEVICE_STORAGE, reading.failed.value)
        listening.cancel()
    }

    /** «Повторить» открывает поток заново, и прочитанное снимает отказ. */
    @Test
    fun retryingOpensTheFlowAgain() = runTest {
        val reading = ScreenReading()
        var opened = 0
        val source = flow {
            opened++
            if (opened == 1) throw IllegalStateException("disk I/O error")
            emit(42)
        }
        val values = mutableListOf<Int>()
        val listening = launch { reading.guarded(source).collect { values += it } }
        reading.failed.first { it != null }

        reading.retry()
        testScheduler.runCurrent()

        assertEquals(listOf(42), values)
        assertNull(reading.failed.value)
        listening.cancel()
    }

    /**
     * Разовое чтение при открытии не удалось — повтор зовёт его снова, а удавшееся рядом не
     * перечитывается: оно уже заполнило форму, и повтор затёр бы набранное.
     */
    @Test
    fun retryingCallsOnlyTheReadThatFailed() = runTest {
        val reading = ScreenReading()
        var good = 0
        var bad = 0
        reading.load(this) { good++ }.join()
        reading.load(this) {
            bad++
            if (bad == 1) throw IllegalStateException("disk I/O error")
        }.join()
        assertEquals(Unavailability.DEVICE_STORAGE, reading.failed.value)

        reading.retry()
        testScheduler.advanceUntilIdle()

        assertEquals(1, good)
        assertEquals(2, bad)
        assertNull(reading.failed.value)
    }

    /** Отменённое чтение — не отказ: экран закрыли, и говорить ему «не прочиталось» некому и незачем. */
    @Test
    fun aCancelledReadIsNotAFailure() = runTest {
        val reading = ScreenReading()
        val never = CompletableDeferred<Unit>()

        val job = reading.load(this) { never.await() }
        yield()
        job.cancel()
        job.join()

        assertNull(reading.failed.value)
    }

    /** Повтор без отказа ничего не делает: открывать заново то, что читается, незачем. */
    @Test
    fun retryingWithoutAFailureDoesNothing() = runTest {
        val reading = ScreenReading()
        var opened = 0
        val values = mutableListOf<Int>()
        // Слушатель жив, пока зовут повтор: иначе переоткрывать было бы некому, и проверка не могла бы покраснеть.
        val listening = launch { reading.guarded(flow { opened++; emit(opened) }).collect { values += it } }
        testScheduler.runCurrent()

        reading.retry()
        testScheduler.runCurrent()

        assertEquals(listOf(1), values)
        assertEquals(1, opened)
        listening.cancel()
    }
}

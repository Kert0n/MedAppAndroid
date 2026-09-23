package com.kert0n.medapp.ui.plan

import com.kert0n.medapp.ui.intake.IntakeQuestionsDialog
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.ScreenState
import com.kert0n.medapp.presentation.plan.DayItemPresentationDTO
import com.kert0n.medapp.presentation.plan.DayMessage
import com.kert0n.medapp.presentation.plan.DayPermissionsPresentationDTO
import com.kert0n.medapp.presentation.plan.DayPagePresentationDTO
import com.kert0n.medapp.ui.DAY
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.LoadingState
import com.kert0n.medapp.ui.TIME
import com.kert0n.medapp.ui.intake.text
import com.kert0n.medapp.ui.NavigationRow
import com.kert0n.medapp.ui.pack.Marker
import com.kert0n.medapp.ui.words
import java.time.LocalTime
import kotlin.uuid.Uuid

/**
 * На сколько дней вперёд листается план. Столько же вперёд календарь пишет пункты
 * (`CourseCalendar.WINDOW` — шестьдесят дней): дальше страница показывала бы одни ожидаемые дозы,
 * а человек в такую даль не заглядывает. Число здесь, а не в чтении: это предел листания, свойство
 * страницы.
 */
private const val DAYS_AHEAD = 60

/**
 * Страницы дня (PLAN H3 №12).
 *
 * Страница держит **сдвиг**, а не дату: в полночь та же страница начинает показывать новый день,
 * и переставлять её не приходится. Листается только вперёд — назад ответы уже даны, и менять их
 * пока нечем (issue #44), поэтому нулевая страница и есть первая.
 *
 * Чтение каждой страницы спрашивается отдельно — [page] зовётся в вёрстке той страницы, которой
 * оно принадлежит: листающий человек видит две страницы разом, и общее чтение показало бы соседней
 * чужие строки.
 */
@Composable
fun DayPages(
    page: @Composable (daysAhead: Int) -> ScreenState<DayPagePresentationDTO>,
    permissions: DayPermissionsPresentationDTO,
    onOpen: (DayItemPresentationDTO) -> Unit,
    onConfirm: (Uuid) -> Unit,
    onDecline: (Uuid) -> Unit,
    onDismissMessage: () -> Unit,
    onAcknowledge: () -> Unit,
    onDismissQuestion: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixAlarms: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pager = rememberPagerState(pageCount = { DAYS_AHEAD + 1 })
    HorizontalPager(state = pager, modifier = modifier.fillMaxSize()) { daysAhead ->
        val state = page(daysAhead)
        DayPage(state, permissions, onOpen, onConfirm, onDecline, onFixNotifications, onFixAlarms)
        // Записать не вышло — сказано словами: молчание после нажатия человек читает как успех.
        (state as? ScreenState.Ready)?.value?.message?.let {
            AlertDialog(
                onDismissRequest = onDismissMessage,
                text = { Text(it.words()) },
                confirmButton = {
                    TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_got_it)) }
                }
            )
        }
        // Быстрый ответ спрашивает, прежде чем записать: коробка просрочена к дню приёма.
        (state as? ScreenState.Ready)?.value?.question?.let {
            IntakeQuestionsDialog(it.questions, onAcknowledge = onAcknowledge, onDismiss = onDismissQuestion)
        }
    }
}

/** Слова беды подбирает экран: причина — значение, и текст к ней живёт в `R.string.*` (PLAN H1). */
@Composable
internal fun DayMessage.words(): String = when (this) {
    is DayMessage.Refused -> stringResource(reason.text)
    DayMessage.AlreadyAnswered -> stringResource(R.string.intake_already_answered)
    DayMessage.EpisodeClosed -> stringResource(R.string.intake_episode_closed)
    DayMessage.Gone -> stringResource(R.string.intake_card_gone)
}

/** Один день: заголовок с датой, пункты и то, что пришлось на этот день помимо них. */
@Composable
private fun DayPage(
    state: ScreenState<DayPagePresentationDTO>,
    permissions: DayPermissionsPresentationDTO,
    onOpen: (DayItemPresentationDTO) -> Unit,
    onConfirm: (Uuid) -> Unit,
    onDecline: (Uuid) -> Unit,
    onFixNotifications: () -> Unit,
    onFixAlarms: () -> Unit
) {
    val day = (state as? ScreenState.Ready)?.value
    // Первое чтение базы ещё не пришло: говорить «ничего не назначено» рано — это была бы неправда.
    if (day == null) {
        LoadingState(Modifier.fillMaxSize())
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = day.header(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        // Что мешает напомнить вовремя — над днём: человек пришёл сам, потому что телефон
        // промолчал, и первое, что он должен узнать, — почему (PLAN H3 «Уведомления на экране»).
        if (permissions.notificationsOff) {
            NavigationRow(
                icon = R.drawable.ic_warning,
                text = stringResource(R.string.plan_notifications_off),
                supporting = stringResource(R.string.plan_notifications_off_hint),
                onClick = onFixNotifications,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (permissions.intakesMuted) {
            NavigationRow(
                icon = R.drawable.ic_warning,
                text = stringResource(R.string.plan_intakes_muted),
                supporting = stringResource(R.string.plan_intakes_muted_hint),
                onClick = onFixNotifications,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (permissions.alarmsInexact) {
            NavigationRow(
                icon = R.drawable.ic_schedule,
                text = stringResource(R.string.plan_alarms_inexact),
                supporting = stringResource(R.string.plan_alarms_inexact_hint),
                onClick = onFixAlarms
            )
        }
        if (day.isEmpty) {
            EmptyState(text = stringResource(R.string.plan_day_empty), modifier = Modifier.fillMaxSize())
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(day.items, key = { it.key }) { item -> DayCard(item, onOpen, onConfirm, onDecline) }
            shelf(R.string.plan_day_also, day.alsoOnThisDay, onOpen)
        }
    }
}

/**
 * Заголовок дня: число и — для ближних дней — слово. «Сегодня» человек читает быстрее, чем
 * сравнивает даты, а дальше третьего дня слова нет: «через пять дней» не короче самой даты.
 */
@Composable
private fun DayPagePresentationDTO.header(): String {
    val word = when (daysAhead) {
        0 -> stringResource(R.string.plan_day_today)
        1 -> stringResource(R.string.plan_day_tomorrow)
        else -> null
    }
    val number = DAY.format(date)
    return if (word == null) number else "$number · $word"
}

/** Полка «ещё в этот день»: пустая не показывается — заголовок без строк ничего не говорит. */
private fun LazyListScope.shelf(
    @StringRes title: Int,
    items: List<DayItemPresentationDTO>,
    onOpen: (DayItemPresentationDTO) -> Unit
) {
    if (items.isEmpty()) return
    item(key = "shelf-$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    items(items, key = { it.key }) { item -> DayCard(item, onOpen, onConfirm = {}, onDecline = {}) }
}

/**
 * Пункт дня — **карточка**, а не строка списка (замечание владельца 2026-09-16). У пункта есть свои
 * действия, а действие внутри строки без границы читается как действие всего списка; Material 3
 * отводит карточке ровно этот случай — содержимое и действия об одном предмете. Список аптечек
 * устроен так же.
 *
 * Слева — время и состояние в одной строке, под ними лечение, доза и коробка; справа, по центру по
 * вертикали, — действия столбиком. Текст забирает остаток ширины, кнопки меряются своими словами:
 * вес на кнопке уравнял бы их и порезал текст, а действие в отдельной нижней строке растягивало
 * карточку под одну кнопку (замечания владельца 2026-09-16). Значок повторяет слово глазу и потому
 * экранному чтецу не адресуется.
 *
 * Нажимается карточка целиком и ведёт на карточку пункта — туда, где приём меняют, а не отвечают
 * наспех.
 */
@Composable
internal fun DayCard(
    item: DayItemPresentationDTO,
    onOpen: (DayItemPresentationDTO) -> Unit,
    onConfirm: (Uuid) -> Unit,
    onDecline: (Uuid) -> Unit
) {
    val intakeId = item.intakeId
    ElevatedCard(
        onClick = { onOpen(item) },
        enabled = intakeId != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Текст забирает остаток ширины, действия меряются своими словами: вес на кнопке
            // уравнял бы их и порезал текст (замечание владельца 2026-09-16).
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Переносится, а не сжимается: в узком окне (попап пропущенного, Sm29, крупный шрифт)
                // метка иначе получала нулевую ширину и вставала столбиком по букве (снимок BigLatest).
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    // День стоит у строки, только если он не сегодняшний: у вчерашнего
                    // обязательства время без дня ничего не говорит.
                    Text(
                        listOfNotNull(item.on?.let { DAY.format(it) }, TIME.format(item.at)).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (item.tellsItsState) {
                        Marker(item.state.icon, item.stateWords(), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    item.details(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Действия — столбиком справа и по центру: одно действие не заводит под себя целую
            // новую строку и не растягивает карточку.
            if (intakeId != null && (item.canDecline || item.canConfirm)) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (item.canConfirm) {
                        FilledTonalButton(onClick = { onConfirm(intakeId) }, enabled = !item.isAnswering) {
                            Text(stringResource(R.string.intake_confirm))
                        }
                    }
                    if (item.canDecline) {
                        TextButton(onClick = { onDecline(intakeId) }, enabled = !item.isAnswering) {
                            Text(stringResource(R.string.intake_decline))
                        }
                    }
                }
            }
        }
    }
}

/** Доза и, если она известна, пачка: «2 таблетка · Нурофен». */
private fun DayItemPresentationDTO.details(): String =
    listOfNotNull(dose.words(), packageName).joinToString(" · ")

/**
 * Состояние словами. У принятого рядом стоит время: «принят в 09:12» — то, что человек и хотел
 * узнать, открывая день.
 */
@Composable
private fun DayItemPresentationDTO.stateWords(): String = when (state) {
    DayItemPresentationDTO.State.PLANNED -> stringResource(R.string.intake_state_planned)
    DayItemPresentationDTO.State.TAKEN -> stringResource(R.string.intake_state_taken, answeredAt.words())
    DayItemPresentationDTO.State.MISSED -> stringResource(R.string.intake_state_missed)
    DayItemPresentationDTO.State.CANCELLED -> stringResource(R.string.intake_state_cancelled)
    DayItemPresentationDTO.State.EXPECTED -> stringResource(R.string.intake_state_expected)
    DayItemPresentationDTO.State.ONE_OFF -> stringResource(R.string.intake_state_one_off, answeredAt.words())
}

/** Время ответа — у принятого оно есть всегда; пустая строка тут значила бы потерянный факт. */
private fun LocalTime?.words(): String = this?.let { TIME.format(it) }.orEmpty()

@get:DrawableRes
private val DayItemPresentationDTO.State.icon: Int
    get() = when (this) {
        DayItemPresentationDTO.State.PLANNED -> R.drawable.ic_schedule
        DayItemPresentationDTO.State.TAKEN -> R.drawable.ic_check_circle
        DayItemPresentationDTO.State.MISSED -> R.drawable.ic_warning
        DayItemPresentationDTO.State.CANCELLED -> R.drawable.ic_close
        DayItemPresentationDTO.State.EXPECTED -> R.drawable.ic_calendar_month
        DayItemPresentationDTO.State.ONE_OFF -> R.drawable.ic_medication
    }

/**
 * Чем строка отличается от соседних в списке. У пункта за окном календаря записи ещё нет, и
 * своего номера тоже: его называют лечение и время, которых на день приходится не больше одного.
 */
internal val DayItemPresentationDTO.key: String
    get() = intakeId?.toString() ?: "$courseId-$at"

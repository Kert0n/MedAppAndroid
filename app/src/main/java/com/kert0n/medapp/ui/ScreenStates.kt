package com.kert0n.medapp.ui

import androidx.activity.compose.BackHandler
import com.kert0n.medapp.presentation.ScreenReading
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kert0n.medapp.R
import com.kert0n.medapp.domain.Unavailability

/**
 * Показывать нечего — три случая, и путать их нельзя: «ещё не прочитано», «не вышло, вот
 * почему» и «пусто, потому что человек ничего не завёл». Выглядят они одинаково на всех
 * экранах — иначе каждый следующий экран изобретает своё.
 */

/**
 * Ожидание. Подписи нет, но она есть у экранного чтеца: кружок сам по себе ему ничего не говорит.
 *
 * [text] называет, **чего** ждут, когда ждать приходится заметно долго и не по своей воле: «читаем
 * из базы» человеку знать незачем, а «проверяем код маркировки» на плохой связи — единственное,
 * что отличает работу от зависшего экрана.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String? = null) {
    val description = stringResource(R.string.state_loading)
    Middle(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = description })
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Ничего не заведено. Это не отказ: показывать нечего, потому что человек ещё ничего не создал,
 * — и [actionText] предлагает создать, если экрану есть что предложить.
 *
 * [icon] называет, о чём речь, там, где одного текста мало: перечёркнутый объектив на пустом
 * экране сканера говорит о камере раньше, чем человек дочитает фразу.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    @DrawableRes icon: Int? = null
) = Told(
    text = text,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier,
    actionText = actionText,
    onAction = onAction,
    icon = icon
)

/**
 * Не вышло, и сказано почему. Повтор предлагается там, где он осмыслен: у причины без повтора
 * кнопки нет — нажимать на неё значило бы обещать человеку то, чего не будет.
 */
@Composable
fun ErrorMessage(
    reason: Unavailability,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) = ErrorMessage(stringResource(reason.text), modifier, onRetry.takeIf { reason.isWorthRetrying })

/**
 * Та же беда словами вызывающего — для случаев, у которых своей [Unavailability] нет: утрата
 * ключа это не «не загрузилось», а состояние, из которого человек выходит решением.
 */
@Composable
fun ErrorMessage(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) = Told(
    text = text,
    color = MaterialTheme.colorScheme.error,
    modifier = modifier,
    actionText = stringResource(R.string.action_retry).takeIf { onRetry != null },
    onAction = onRetry
)

/** Сказанное человеку посреди пустого экрана и, если есть что делать, кнопка. */
@Composable
private fun Told(
    text: String,
    color: Color,
    modifier: Modifier,
    actionText: String?,
    onAction: (() -> Unit)?,
    @DrawableRes icon: Int? = null
) = Middle(modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Значок молчит для экранного чтеца: он говорит глазу то же, что сказано текстом под ним.
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(48.dp)
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(actionText)
            }
        }
    }
}

/** Общая рамка всех трёх: середина экрана и поля, одинаковые везде. */
@Composable
private fun Middle(modifier: Modifier, content: @Composable () -> Unit) = Box(
    modifier = modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center,
    content = { content() }
)

/** Текст причины — её свойство: экран не выбирает, какими словами называть отказ. Общий на все экраны. */
@get:StringRes
internal val Unavailability.text: Int
    get() = when (this) {
        Unavailability.NO_CONNECTION -> R.string.failure_no_connection
        Unavailability.SERVER_SILENT -> R.string.failure_server_unavailable
        Unavailability.SERVER_REFUSED_US -> R.string.failure_not_authorized
        Unavailability.DEVICE_STORAGE -> R.string.failure_device_storage
    }

/** Повтор тем же осмыслен не всегда: отказ в пропуске им не лечится (PLAN G2). */
private val Unavailability.isWorthRetrying: Boolean
    get() = this != Unavailability.SERVER_REFUSED_US

/**
 * Экран, чьё чтение может не удаться. Прочиталось — показан он сам; не прочиталось — вместо него
 * сказано, что данные устройства недоступны, и предложено повторить. [onBack] есть у экранов в
 * глубине: уйти с экрана, которому нечего показать, человек должен так же, как с любого другого.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Readable(
    vararg readings: ScreenReading,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val failed = readings.map { it.failed.collectAsStateWithLifecycle().value }.firstOrNull { it != null }
    if (failed == null) {
        content()
        return
    }
    // Системный «назад» ведёт туда же, куда стрелка: у листа приёма это закрыть лист, а не уйти с
    // карточки целиком.
    if (onBack != null) BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
            }
        }
    ) { padding ->
        ErrorMessage(failed, Modifier.padding(padding), onRetry = { readings.forEach { it.retry() } })
    }
}

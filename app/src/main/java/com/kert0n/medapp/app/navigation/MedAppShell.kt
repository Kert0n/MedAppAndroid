package com.kert0n.medapp.app.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.saveable.rememberSaveable
import com.kert0n.medapp.presentation.course.CourseFormUiState
import com.kert0n.medapp.presentation.course.CourseFormViewModel
import com.kert0n.medapp.presentation.course.CourseCardViewModel
import com.kert0n.medapp.presentation.course.CourseListViewModel
import com.kert0n.medapp.presentation.course.CoursePresentationDTO
import com.kert0n.medapp.presentation.course.CourseSourcesViewModel
import com.kert0n.medapp.presentation.course.SourcePickingViewModel
import com.kert0n.medapp.ui.course.CourseCardScreen
import com.kert0n.medapp.ui.course.CourseFormScreen
import com.kert0n.medapp.ui.course.CourseSourcesScreen
import com.kert0n.medapp.ui.course.SourcePickingScreen
import com.kert0n.medapp.presentation.intake.UnplannedIntakeViewModel
import com.kert0n.medapp.ui.intake.UnplannedIntakeSheet
import com.kert0n.medapp.presentation.intake.IntakeCardViewModel
import com.kert0n.medapp.presentation.intake.IntakeHistoryViewModel
import com.kert0n.medapp.domain.notification.NotificationTarget
import com.kert0n.medapp.presentation.notification.ExpiringTodayViewModel
import com.kert0n.medapp.presentation.plan.DayPlanViewModel
import com.kert0n.medapp.presentation.plan.MissedIntakesViewModel
import com.kert0n.medapp.ui.plan.MissedIntakesPopup
import com.kert0n.medapp.ui.intake.IntakeCardScreen
import com.kert0n.medapp.ui.openAppSettings
import com.kert0n.medapp.ui.openExactAlarmSettings
import com.kert0n.medapp.ui.openNotificationSettings
import com.kert0n.medapp.ui.rememberCameraPermissionRequest
import com.kert0n.medapp.ui.rememberNotificationPermissionRequest
import com.kert0n.medapp.ui.intake.IntakeHistoryScreen
import com.kert0n.medapp.ui.notification.ExpiringTodayPopup
import com.kert0n.medapp.ui.plan.PlanMode
import com.kert0n.medapp.ui.plan.PlanScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.NavDisplay
import com.kert0n.medapp.R
import com.kert0n.medapp.presentation.medkit.MedKitFormUiState
import com.kert0n.medapp.presentation.medkit.MedKitFormViewModel
import com.kert0n.medapp.presentation.medkit.MedKitJoiningViewModel
import com.kert0n.medapp.presentation.medkit.MedKitSharingViewModel
import com.kert0n.medapp.presentation.medkit.MedKitListViewModel
import com.kert0n.medapp.presentation.pack.MedKitContentsViewModel
import com.kert0n.medapp.presentation.operation.SyncStatusViewModel
import com.kert0n.medapp.presentation.operation.OptionsViewModel
import com.kert0n.medapp.presentation.settings.LanguageViewModel
import com.kert0n.medapp.presentation.settings.PermissionsViewModel
import com.kert0n.medapp.presentation.settings.SettingsUiState
import com.kert0n.medapp.presentation.settings.SettingsViewModel
import com.kert0n.medapp.ui.settings.LanguageScreen
import com.kert0n.medapp.ui.settings.PermissionsScreen
import com.kert0n.medapp.ui.settings.SettingsScreen
import com.kert0n.medapp.presentation.pack.PackageCardViewModel
import com.kert0n.medapp.presentation.pack.PackageFormViewModel
import com.kert0n.medapp.presentation.pack.PackageRecountViewModel
import com.kert0n.medapp.presentation.pack.PackageTransferViewModel
import com.kert0n.medapp.presentation.scan.ScannerCamera
import com.kert0n.medapp.presentation.scan.ScannerViewModel
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.medkit.MedKitContentsScreen
import com.kert0n.medapp.ui.medkit.MedKitFormScreen
import com.kert0n.medapp.ui.medkit.MedKitJoiningScreen
import com.kert0n.medapp.ui.medkit.MedKitSharingScreen
import com.kert0n.medapp.ui.medkit.MedKitListScreen
import com.kert0n.medapp.ui.pack.PackageCardScreen
import com.kert0n.medapp.ui.pack.PackageFormScreen
import com.kert0n.medapp.ui.pack.PackageRecountScreen
import com.kert0n.medapp.ui.pack.PackageTransferScreen
import com.kert0n.medapp.ui.operation.OptionsScreen
import com.kert0n.medapp.ui.operation.SyncStatusScreen
import com.kert0n.medapp.ui.scan.ScannerScreen

/**
 * Оболочка приложения: пять мест внизу и содержимое над ними. Где человек стоит и как глубоко —
 * живёт в [TabStacks] и переживает поворот и смерть процесса.
 *
 * **Движения у оболочки нет вовсе: экран просто сменяется** (решение владельца 2026-09-15).
 * Раскладка — как у референса (HomeMedkit-App): содержимое места и панель мест, панель только у
 * мест. Собственная система движения поверх библиотеки, которая ведёт предиктивный жест
 * по-своему, — это борьба с библиотекой, и прошлый заход на ней и кончился (отклонённый PR #33).
 *
 * **Отступы системы оболочка не только отдаёт, но и поглощает.** `Modifier.padding(padding)`
 * оставляет место под строкой состояния и полосой жестов — и только; сами вставки остаются
 * видны тому, кто внутри, и свой `Scaffold` каждого экрана берёт их второй раз. Беда одна на
 * все экраны, поэтому и лечится она здесь.
 */
@Composable
fun MedAppShell(
    modifier: Modifier = Modifier,
    stacks: TabStacks = rememberTabStacks(),
    opening: NotificationTarget? = null,
    onOpened: () -> Unit = {}
) {
    // Режим места «План» держит оболочка: уведомление о плане дня ведёт прямо на день, а не на
    // список курсов, и знать об этом должен тот, кто применяет цель (PLAN H3 «Уведомления»).
    // Состояние, а не значение: читает его сам экран места, и подписка на изменение остаётся у
    // него. Передай значением — список экранов пришлось бы собирать заново на каждую смену
    // режима, и `NavDisplay` показал бы прежний.
    val planMode = rememberSaveable { mutableStateOf(PlanMode.COURSES) }
    // Прочитанный камерой ключ приглашения — **в памяти оболочки**, а не в маршруте: ключ секрет, а
    // маршрут ложится в сохранённую стопку (PLAN G3). Поэтому и не `rememberSaveable`: пережить
    // смерть процесса ключ не должен, как не переживает её ключ на экране 21. Живёт он ровно от
    // сканера до вступления, которое его забирает.
    val scanned = remember { mutableStateOf<String?>(null) }
    // Цель применяется **один раз**: иначе поворот экрана возвращал бы человека туда, откуда он
    // уже ушёл. Намерение опустошает окно, а эта проверка бережёт от повторного применения.
    LaunchedEffect(opening) {
        when (val target = opening ?: return@LaunchedEffect) {
            is NotificationTarget.Intake -> stacks.go(Screen.IntakeCard(target.intakeId))
            is NotificationTarget.PackageCard -> stacks.go(Screen.PackageCard(target.packageId))
            is NotificationTarget.CourseSources -> stacks.go(Screen.CourseSources(target.courseId))
            // Сводка ведёт на план дня: даты в маршруте нет — страница дня держит сдвиг, а не
            // число, и «сегодня» у неё своё (H3 №12).
            is NotificationTarget.DayPlan -> {
                planMode.value = PlanMode.DAY
                stacks.go(Place.PLAN.key)
            }
            // Уведомление обещало очередь — на неё и ведём, а не в корень места.
            NotificationTarget.SyncStatus -> {
                stacks.go(Place.OPTIONS.key)
                stacks.go(Screen.SyncStatus)
            }
        }
        onOpened()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Панель мест — у мест: в глубине человек занят одним делом, и пять соседних комнат
        // ему не нужны. Появляется и исчезает вместе с экраном, без своего движения.
        bottomBar = { if (stacks.screen in PLACES) Places(stacks) }
    ) { padding ->
        NavDisplay(
            entries = stacks.entries(remember(stacks, planMode, scanned) { screens(stacks, planMode, scanned) }),
            onBack = stacks::back,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            transitionSpec = { SWITCH },
            popTransitionSpec = { SWITCH },
            predictivePopTransitionSpec = { SWITCH }
        )
        // Попап срока живёт **над** местами, а не в одном из них: уход на карточку коробки — это
        // тот же разговор, и возврат его не обрывает (PLAN H3 «Уведомления на экране»). Показан он
        // только **на** месте: окно модальное, и над карточкой коробки оно не давало бы с ней
        // ничего сделать — «Выбросить» и «назад» не отвечали (PLAN C1 «Попап вне мест»). Модель
        // живёт и в глубине: вернулся — попап на месте, с живым содержимым.
        val expiring: ExpiringTodayViewModel = hiltViewModel()
        val expiringState = expiring.state.collectAsStateWithLifecycle().value
        // Попап пропущенного — последний шанс ответить за прошлые дни (PLAN C1): там же, на
        // местах. **Сначала пропуски, потом срок**: из попапа срока коробку выбрасывают, и
        // ответить за вчерашний приём из неё было бы уже нечем (снимок BigLatest, решение
        // владельца 2026-09-16). Два окна разом человек не читает.
        val missed: MissedIntakesViewModel = hiltViewModel()
        val missedState = missed.state.collectAsStateWithLifecycle().value
        if (stacks.screen in PLACES) {
            if (!missedState.isEmpty) {
                MissedIntakesPopup(
                    state = missedState,
                    onOpen = { row -> row.intakeId?.let { stacks.go(Screen.IntakeCard(it)) } },
                    onConfirm = missed::confirm,
                    onDismiss = missed::dismiss,
                    onDismissMessage = missed::dismissMessage
                )
            } else {
                ExpiringTodayPopup(
                    state = expiringState,
                    onOpenPackage = { stacks.go(Screen.PackageCard(it)) },
                    onDismiss = expiring::dismiss
                )
            }
        }
    }
}

/**
 * Что показывает каждый ключ. Пока своего экрана у места нет, за ним стоит общее «пусто» — то
 * самое, которое потом покажет настоящий экран, когда показывать действительно нечего.
 *
 * Состояние экрану даёт `hiltViewModel` здесь же, а аргумент приходит **значением из ключа**:
 * экран получает `state` и действия и больше ничего (PLAN H1).
 */
private fun screens(
    stacks: TabStacks,
    planMode: MutableState<PlanMode>,
    scanned: MutableState<String?>
) = entryProvider<NavKey> {
    entry(Screen.MedKits) {
        val model: MedKitListViewModel = hiltViewModel()
        MedKitListScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onOpen = { stacks.go(Screen.MedKitContents(it)) },
            onAdd = { stacks.go(Screen.MedKitForm()) },
            onJoin = { stacks.go(Screen.MedKitJoining) },
            // Ища лекарство, человек не помнит, в какой оно аптечке: поиск ведёт в область
            // «везде», то есть в тот же экран без названной полки.
            onSearch = { stacks.go(Screen.MedKitContents()) }
        )
    }
    entry<Screen.MedKitForm> { key ->
        val model = hiltViewModel<MedKitFormViewModel, MedKitFormViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.medKitId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Записанное — повод уйти: человек заводил полку, а не форму, и возвращаться ему в неё
        // незачем.
        LaunchedEffect(state) { if (state is MedKitFormUiState.Editing && state.isSaved) stacks.back() }
        MedKitFormScreen(
            state = state,
            onEdit = model::edit,
            onSave = model::save,
            onCancel = stacks::back
        )
    }
    entry<Screen.MedKitContents> { key ->
        val model = hiltViewModel<MedKitContentsViewModel, MedKitContentsViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.medKitId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Полки больше нет — смотреть её содержимое незачем. Дождалась ли она сервера или
        // ушла сразу, видно в списке: там она либо исчезла, либо помечена.
        LaunchedEffect(state.isRemoved) { if (state.isRemoved) stacks.back() }
        MedKitContentsScreen(
            state = state,
            onSearch = model::search,
            onNarrow = model::narrow,
            onOrder = model::order,
            onReset = model::reset,
            onOpen = { stacks.go(Screen.PackageCard(it)) },
            onAdd = { stacks.go(Screen.PackageForm(medKitId = key.medKitId)) },
            onEdit = { stacks.go(Screen.MedKitForm(key.medKitId)) },
            // Делиться можно только названной полкой: у «всех лекарств» её нет, и меню там не
            // показывается вовсе.
            onShare = { key.medKitId?.let { stacks.go(Screen.MedKitSharing(it)) } },
            onAskToRemove = model::askToRemove,
            onPickTarget = model::pickTarget,
            onDismissRemoval = model::dismissRemoval,
            onRemove = model::remove,
            onLeave = model::leave,
            onBack = stacks::back
        )
    }
    entry(Screen.MedKitJoining) {
        val model: MedKitJoiningViewModel = hiltViewModel()
        val state = model.state.collectAsStateWithLifecycle().value
        // Вошёл — идём в саму полку: человек вступал ради лекарств, а не ради формы. Экран формы
        // из стопки уходит, чтобы «назад» с полки вело к списку, а не к введённому коду.
        LaunchedEffect(state.joined) {
            state.joined?.let {
                stacks.back()
                stacks.go(Screen.MedKitContents(it))
            }
        }
        // Пришли со сканера — код уже прочитан, и переписывать его человеку незачем. Ключ
        // забирается один раз: иначе поворот экрана возвращал бы в поле то, что человек стёр.
        LaunchedEffect(Unit) {
            scanned.value?.let {
                model.type(it)
                scanned.value = null
            }
        }
        // Разрешённая камера открывается сразу, неразрешённая сперва спрашивает: просьба стоит
        // там, где человек нажал «Отсканировать», и объяснять её не нужно.
        val askForCamera = rememberCameraPermissionRequest { granted ->
            if (granted) model.scan() else model.cameraDenied()
        }
        MedKitJoiningScreen(
            state = state,
            onType = model::type,
            onJoin = model::join,
            onBack = stacks::back,
            onScan = askForCamera,
            onStopScanning = model::stopScanning,
            onCode = model::seen
        )
    }
    entry<Screen.MedKitSharing> { key ->
        val model = hiltViewModel<MedKitSharingViewModel, MedKitSharingViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.medKitId) }
        )
        MedKitSharingScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onAsk = model::ask,
            onDismissAsking = model::dismissAsking,
            onPublish = model::publish,
            onInvite = model::invite,
            onShowFullScreen = model::showFullScreen,
            onHideFullScreen = model::hideFullScreen,
            onBack = stacks::back
        )
    }
    entry<Screen.PackageForm> { key ->
        val model = hiltViewModel<PackageFormViewModel, PackageFormViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory ->
                factory.create(
                    PackageFormViewModel.Opened(key.medKitId, key.packageId, key.scannedCode)
                )
            }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Заведённая коробка открывается карточкой: человек заводил её, чтобы посмотреть.
        // Поправленная — нет: её карточка и так лежит под формой.
        LaunchedEffect(state.saved) {
            val saved = state.saved ?: return@LaunchedEffect
            stacks.back()
            if (key.packageId == null) stacks.go(Screen.PackageCard(saved))
        }
        PackageFormScreen(
            state = state,
            onEdit = model::edit,
            onPick = model::pick,
            onSave = model::save,
            onCancel = stacks::back,
            // Количество здесь показано, но не правится: у пересчёта свой экран (H3 №8).
            onRecount = { key.packageId?.let { stacks.go(Screen.PackageRecount(it)) } }
        )
    }
    entry<Screen.PackageCard> { key ->
        val model = hiltViewModel<PackageCardViewModel, PackageCardViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.packageId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Выброшенной коробке карточки нет: уходим туда, откуда пришли.
        LaunchedEffect(state.isRemoved) { if (state.isRemoved) stacks.back() }
        // Разовый приём — лист над карточкой (H3 №10): своего места в стопке у него нет, и
        // коробку из виду человек не теряет.
        var taking by rememberSaveable { mutableStateOf(false) }
        PackageCardScreen(
            state = state,
            onEdit = { stacks.go(Screen.PackageForm(packageId = key.packageId)) },
            onTake = { taking = true },
            onHistory = { stacks.go(Screen.IntakeHistory(packageId = key.packageId)) },
            onRecount = { stacks.go(Screen.PackageRecount(key.packageId)) },
            onTransfer = { stacks.go(Screen.PackageTransfer(key.packageId)) },
            onAskToRemove = model::askToRemove,
            onConfirmRemoval = model::remove,
            onDismissRemoval = model::dismissRemoval,
            onBack = stacks::back
        )
        if (taking) {
            val intake = hiltViewModel<UnplannedIntakeViewModel, UnplannedIntakeViewModel.Factory>(
                key = "intake-${key.packageId}",
                creationCallback = { factory -> factory.create(key.packageId) }
            )
            val taken = intake.state.collectAsStateWithLifecycle().value
            // Записано — лист закрывается: человек сказал, что хотел, а новое число покажет
            // карточка. Разговор при этом забывается: следующее «Принять» начинает новый приём, и
            // без этого лист открылся бы уже закрытым (разбор 2026-09-16).
            LaunchedEffect(taken.isRecorded, taken.isGone) {
                if (!taken.isRecorded && !taken.isGone) return@LaunchedEffect
                taking = false
                intake.forgetTheIntake()
            }
            UnplannedIntakeSheet(
                state = taken,
                onEdit = intake::edit,
                onRecord = { intake.record() },
                onAcknowledge = { intake.record(acknowledged = true) },
                onDismissQuestions = intake::dismissQuestions,
                onDismiss = {
                    taking = false
                    intake.forgetTheIntake()
                }
            )
        }
    }
    entry<Screen.PackageRecount> { key ->
        val model = hiltViewModel<PackageRecountViewModel, PackageRecountViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.packageId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Записано — уходим: кончившейся коробке карточки нет, а у оставшейся число покажет она сама.
        LaunchedEffect(state.isDone) { if (state.isDone) stacks.back() }
        PackageRecountScreen(
            state = state,
            onEdit = model::edit,
            onSubmit = model::submit,
            onCancel = stacks::back
        )
    }
    entry<Screen.PackageTransfer> { key ->
        val model = hiltViewModel<PackageTransferViewModel, PackageTransferViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.packageId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Переехала или поехала — решение принято, и экран уходит: новое место покажет карточка.
        LaunchedEffect(state.isDone) { if (state.isDone) stacks.back() }
        PackageTransferScreen(
            state = state,
            onChoose = model::choose,
            onTransfer = model::transfer,
            onBack = stacks::back
        )
    }
    entry(Screen.Plan) {
        val model: CourseListViewModel = hiltViewModel()
        val days: DayPlanViewModel = hiltViewModel()
        // Режим — состояние места: он переживает уход в другую комнату и возвращение, как и
        // всё, что держит стопка (rememberSaveable под своим ключом маршрута).
        val context = LocalContext.current
        // Разрешения человек меняет у системы: вернулся — спрашиваем заново, своего мнения о них
        // приложение не держит (PLAN H3 «Уведомления на экране»).
        LifecycleResumeEffect(days) {
            days.refreshPermissions()
            onPauseOrDispose { }
        }
        PlanScreen(
            mode = planMode.value,
            onMode = { planMode.value = it },
            courses = model.state.collectAsStateWithLifecycle().value,
            // Чтение спрашивается у той страницы, которой оно принадлежит: сдвиг называет вёрстка
            // страницы, а не оболочка.
            dayPage = { daysAhead -> days.page(daysAhead).collectAsStateWithLifecycle().value },
            // Нажатие на строку ведёт на карточку пункта; у дозы за окном календаря записи ещё
            // нет, и открывать по ней нечего.
            onOpenIntake = { item -> item.intakeId?.let { stacks.go(Screen.IntakeCard(it)) } },
            onConfirmIntake = days::confirm,
            onDeclineIntake = days::decline,
            onDismissDayMessage = days::dismissMessage,
            onFixNotifications = context::openNotificationSettings,
            onFixAlarms = context::openExactAlarmSettings,
            dayPermissions = days.permissions.collectAsStateWithLifecycle().value,
            // Черновик открывается редактором, идущее и законченное лечение — карточкой.
            onOpenCourse = { course ->
                stacks.go(
                    if (course.kind == CoursePresentationDTO.Kind.DRAFT) Screen.CourseForm(course.id)
                    else Screen.CourseCard(course.id)
                )
            },
            onAddCourse = { stacks.go(Screen.CourseForm()) }
        )
    }
    entry<Screen.IntakeCard> { key ->
        val model = hiltViewModel<IntakeCardViewModel, IntakeCardViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.intakeId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Ответ дан — карточка уходит: человек отвечал на приём, а не заполнял форму.
        LaunchedEffect(state.isDone) { if (state.isDone) stacks.back() }
        IntakeCardScreen(
            state = state,
            onEdit = model::edit,
            onConfirm = { model.confirm() },
            onDecline = model::decline,
            onBack = stacks::back
        )
    }
    entry<Screen.IntakeHistory> { key ->
        val model = hiltViewModel<IntakeHistoryViewModel, IntakeHistoryViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.courseId, key.packageId) }
        )
        IntakeHistoryScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onBack = stacks::back
        )
    }
    entry<Screen.CourseForm> { key ->
        val model = hiltViewModel<CourseFormViewModel, CourseFormViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.courseId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        val askAboutNotifications = rememberNotificationPermissionRequest()
        // Записанное или удалённое — повод уйти: человек заводил лечение, а не форму. Начатое
        // ведёт дальше, к карточке: с этого мига у лечения есть что показывать. За источниками
        // ведёт записанный черновик — до записи подключать коробки не к чему.
        LaunchedEffect(state) {
            if (state !is CourseFormUiState.Editing) return@LaunchedEffect
            val started = state.startedId
            val sources = state.sourcesOf
            when {
                started != null -> {
                    // Лечение только что завело напоминания — вот и повод спросить разрешение:
                    // польза видна в этот же миг (PLAN H3 «Уведомления на экране»).
                    askAboutNotifications()
                    stacks.back()
                    stacks.go(Screen.CourseCard(started))
                }
                sources != null -> {
                    model.sourcesOpened()
                    stacks.go(Screen.CourseSources(sources))
                }
                state.isSaved || state.isDiscarded || state.isLeft -> stacks.back()
            }
        }
        CourseFormScreen(
            state = state,
            onEdit = model::edit,
            onSave = model::save,
            onAskToDiscard = model::askToDiscard,
            onConfirmDiscard = model::discard,
            onDismissDiscard = model::dismissDiscard,
            onKeep = model::keep,
            onDismissLeaving = model::dismissLeaving,
            // Источники есть у любого лечения: новый черновик по этой кнопке сначала запишется.
            onSources = model::openSources,
            onStart = model::start.takeIf { state !is CourseFormUiState.Editing || state.mode != CourseFormUiState.Mode.RUNNING },
            // Уходит с формы не оболочка, а редактор: записанный ради источников черновик спросит.
            onBack = model::leave
        )
    }
    entry<Screen.CourseCard> { key ->
        val model = hiltViewModel<CourseCardViewModel, CourseCardViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.courseId) }
        )
        CourseCardScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onEdit = { stacks.go(Screen.CourseForm(key.courseId)) },
            onSources = { stacks.go(Screen.CourseSources(key.courseId)) },
            // Три выхода из нехватки ведут туда, где они делаются: подключить — выбор источника,
            // переставить выделения — сами источники, докупить — заведение упаковки.
            onAttachSource = { stacks.go(Screen.SourcePicking(key.courseId)) },
            onAddPackage = { stacks.go(Screen.PackageForm()) },
            onHistory = { stacks.go(Screen.IntakeHistory(courseId = key.courseId)) },
            onAskOffPlan = model::askToCountOffPlan,
            onCountOffPlan = model::countOffPlan,
            onDismissOffPlan = model::dismissOffPlan,
            onAskToCancel = model::askToCancel,
            onConfirmCancel = model::cancel,
            onDismissCancel = model::dismissCancel,
            onDismissMessage = model::dismissMessage,
            onBack = stacks::back
        )
    }
    entry<Screen.CourseSources> { key ->
        val model = hiltViewModel<CourseSourcesViewModel, CourseSourcesViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.courseId) }
        )
        CourseSourcesScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onMove = model::move,
            onAllocate = model::allocate,
            onDetach = model::askToDetach,
            onConfirmDetach = model::detach,
            onDismissDetach = model::dismissDetach,
            onDismissMessage = model::dismissMessage,
            onAdd = { stacks.go(Screen.SourcePicking(key.courseId)) },
            onSave = model::save,
            onBack = stacks::back
        )
    }
    entry<Screen.SourcePicking> { key ->
        val model = hiltViewModel<SourcePickingViewModel, SourcePickingViewModel.Factory>(
            key = key.toString(),
            creationCallback = { factory -> factory.create(key.courseId) }
        )
        val state = model.state.collectAsStateWithLifecycle().value
        // Подключённая коробка ждёт человека в стеке: там он и решит, сколько из неё брать.
        // Просроченную прежде называют — один раз (PLAN C1 «Просрочка при планировании»).
        LaunchedEffect(state.isDone) { if (state.isDone) stacks.back() }
        SourcePickingScreen(
            state = state,
            onAttach = model::attach,
            onExpiredSeen = model::expiredSourceSeen,
            onSearch = model::search,
            onBack = stacks::back
        )
    }
    entry(Screen.Options) {
        val model: OptionsViewModel = hiltViewModel()
        // Разрешения и язык хранит система: вернулся — спрашиваем заново (PLAN H3 №27).
        LifecycleResumeEffect(model) {
            model.refresh()
            onPauseOrDispose { }
        }
        OptionsScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onSyncStatus = { stacks.go(Screen.SyncStatus) },
            onSettings = { stacks.go(Screen.Settings) },
            onPermissions = { stacks.go(Screen.Permissions) },
            onLanguage = { stacks.go(Screen.Language) }
        )
    }
    entry(Screen.Language) {
        val model: LanguageViewModel = hiltViewModel()
        LanguageScreen(
            current = model.state.collectAsStateWithLifecycle().value,
            onChoose = model::choose,
            onBack = stacks::back
        )
    }
    entry(Screen.Permissions) {
        val model: PermissionsViewModel = hiltViewModel()
        val context = LocalContext.current
        LifecycleResumeEffect(model) {
            model.refresh()
            onPauseOrDispose { }
        }
        PermissionsScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onFixNotifications = context::openNotificationSettings,
            onFixAlarms = context::openExactAlarmSettings,
            onFixCamera = context::openAppSettings,
            onBack = stacks::back
        )
    }
    entry(Screen.Settings) {
        val model: SettingsViewModel = hiltViewModel()
        val state = model.state.collectAsStateWithLifecycle().value
        // Записанное — повод уйти: человек менял настройки, а не заполнял форму навсегда.
        LaunchedEffect(state) { if (state is SettingsUiState.Editing && state.isSaved) stacks.back() }
        SettingsScreen(
            state = state,
            onEdit = model::edit,
            onSave = model::save,
            onBack = stacks::back
        )
    }
    entry(Screen.SyncStatus) {
        val model: SyncStatusViewModel = hiltViewModel()
        SyncStatusScreen(
            state = model.state.collectAsStateWithLifecycle().value,
            onRefresh = model::refresh,
            // Расхождение по числу лечится пересчётом — тем же экраном, что и обычный пересчёт
            // (REQ-045): второго места для одного дела не заводится.
            onRecount = { stacks.go(Screen.PackageRecount(it)) },
            onDismiss = model::dismiss,
            onDismissMessage = model::dismissMessage,
            onBack = stacks::back
        )
    }
    entry(Screen.Scanner) {
        val model: ScannerViewModel = hiltViewModel()
        val state = model.state.collectAsStateWithLifecycle().value
        // Разрешение меняет человек у системы: вернулся на место — спрашиваем заново, а прочитанный
        // код забываем (PLAN H3 «Набор сканера»).
        LifecycleResumeEffect(model) {
            model.resumed()
            onPauseOrDispose { }
        }
        val askForCamera = rememberCameraPermissionRequest { model.asked() }
        // Просьба — в тот миг, когда она объяснима: человек открыл сканер, камера нужна сейчас.
        // Один раз: спрошенное состояние — уже другое (`REFUSED`), и второй просьбы не будет.
        LaunchedEffect(state.camera) {
            if (state.camera == ScannerCamera.UNASKED) askForCamera()
        }
        // Код с коробки ведёт в форму: спрашивает о нём реестр она сама (PLAN C1).
        LaunchedEffect(state.opening) {
            val code = state.opening ?: return@LaunchedEffect
            model.opened()
            stacks.go(Screen.PackageForm(scannedCode = code))
        }
        // Приглашение ведёт на вступление — **молча и с готовым кодом**, как коробка ведёт в
        // заполненную форму: человек навёл камеру, и переспрашивать его незачем (решение владельца
        // 2026-09-17). Сам ключ едет памятью оболочки, а не ключом маршрута (G3).
        LaunchedEffect(state.invitation) {
            val key = state.invitation ?: return@LaunchedEffect
            model.invitationOpened()
            scanned.value = key
            stacks.go(Screen.MedKitJoining)
        }
        val context = LocalContext.current
        ScannerScreen(
            state = state,
            onCode = model::seen,
            onAllow = askForCamera,
            onOpenSettings = context::openAppSettings
        )
    }
    for (place in Place.entries - Place.MED_KITS - Place.PLAN - Place.OPTIONS - Place.SCANNER) {
        entry(place.key) { NotReadyYet() }
    }
}

private val PLACES: Set<NavKey> = Place.entries.map { it.key }.toSet()

/**
 * Экран просто сменяется: ни проявления, ни движения, ни перехода размера. Переход, у которого
 * есть длительность, быстрые «открыть → назад» перебивают на середине: оба экрана живут вместе,
 * а движение каждый раз стартует с другой точки и выглядит по-разному. У мгновенной смены
 * перебивать нечего.
 */
private val SWITCH = ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)

/**
 * Переключение мест. Повторное нажатие на своё место возвращает его к началу, а переход на
 * чужое сохраняет, где человек был: он вернётся туда же, а не к началу.
 */
@Composable
private fun Places(stacks: TabStacks) {
    NavigationBar {
        for (place in Place.entries) {
            val selected = stacks.place == place.key
            NavigationBarItem(
                selected = selected,
                onClick = { if (selected) stacks.backToRoot(place.key) else stacks.go(place.key) },
                icon = { Icon(painterResource(place.icon(selected)), contentDescription = null) },
                // Подпись в одну строку: на 360 dp пять мест делят экран по 72 dp, и подпись из
                // двух строк полоса обрезала бы (Sm29).
                label = {
                    Text(
                        stringResource(place.label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

/** Заглушка места — с подложкой, как у настоящего экрана: сквозь неё не просвечивает соседнее. */
@Composable
private fun NotReadyYet() = Scaffold { padding ->
    EmptyState(text = stringResource(R.string.screen_not_ready), modifier = Modifier.padding(padding))
}

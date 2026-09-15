package com.kert0n.medapp.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kert0n.medapp.R
import com.kert0n.medapp.ui.EmptyState
import com.kert0n.medapp.ui.medkit.MedKitFormRoute
import com.kert0n.medapp.ui.medkit.MedKitListRoute
import com.kert0n.medapp.ui.pack.PackageFormRoute
import com.kert0n.medapp.ui.pack.PackageRoute
import kotlin.reflect.typeOf
import kotlin.uuid.Uuid

/**
 * Оболочка приложения: пять мест внизу и содержимое над ними. Место, где стоит человек, живёт в
 * [NavHostController] и переживает поворот и смерть процесса — его хранит навигация, а не экран.
 */
@Composable
fun MedAppShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = { MedAppBottomBar(navController) }
    ) { padding ->
        MedAppNavHost(navController, Modifier.padding(padding))
    }
}

/**
 * Переключение мест. Повторное нажатие на своё место возвращает к его началу, а переход на чужое
 * сохраняет, где человек был: он вернётся туда же, а не к началу (`saveState`/`restoreState`).
 */
@Composable
private fun MedAppBottomBar(navController: NavController) {
    val entry by navController.currentBackStackEntryAsState()
    val here = entry?.destination
    NavigationBar {
        for (destination in Destination.entries) {
            val selected = here?.leadsTo(destination) == true
            NavigationBarItem(
                selected = selected,
                onClick = { navController.goTo(destination) },
                icon = { Icon(painterResource(destination.icon(selected)), contentDescription = null) },
                // Подпись в одну строку: на 360 dp пять мест делят экран по 72 dp, и «Настройки»
                // переносились второй строкой, которую полоса обрезала (Sm29).
                label = {
                    Text(
                        stringResource(destination.label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

/**
 * Содержимое мест и экраны вглубь. За местом, экрана у которого ещё нет, стоит общее «пусто» —
 * то самое, которое потом покажет настоящий экран, когда показывать действительно нечего.
 *
 * Как идентификатор едет в аргументе, навигация сама не знает: тип называется здесь, один раз на
 * все маршруты, которые его возят (`UuidNavType`, `UuidOrNoneNavType`).
 */
@Composable
private fun MedAppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = Route.MedKits, modifier = modifier) {
        composable<Route.MedKits> {
            MedKitListRoute(
                onOpen = { navController.navigate(Route.MedKitContents(it)) },
                onAdd = { navController.navigate(Route.MedKitForm()) },
                onSearch = { navController.navigate(Route.AllPackages) }
            )
        }
        composable<Route.MedKitForm>(typeMap = mapOf(typeOf<Uuid?>() to UuidOrNoneNavType)) {
            MedKitFormRoute(onDone = { navController.popBackStack() })
        }
        composable<Route.MedKitContents>(typeMap = mapOf(typeOf<Uuid>() to UuidNavType)) { NotReadyYet() }
        composable<Route.PackageForm>(typeMap = mapOf(typeOf<Uuid?>() to UuidOrNoneNavType)) {
            PackageFormRoute(
                // Записанная коробка открывается карточкой, а форма со стека уходит: человек
                // заводил её, чтобы на неё посмотреть, а не чтобы завести вторую такую же.
                onSaved = { packageId ->
                    navController.navigate(Route.Package(packageId)) {
                        popUpTo<Route.PackageForm> { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable<Route.Package>(typeMap = mapOf(typeOf<Uuid>() to UuidNavType)) {
            PackageRoute(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Route.PackageForm(packageId = it)) },
                onChangeAmount = { navController.navigate(Route.PackageAmount(it)) },
                onTransfer = { navController.navigate(Route.PackageTransfer(it)) }
            )
        }
        composable<Route.PackageAmount>(typeMap = mapOf(typeOf<Uuid>() to UuidNavType)) { NotReadyYet() }
        composable<Route.PackageTransfer>(typeMap = mapOf(typeOf<Uuid>() to UuidNavType)) { NotReadyYet() }
        composable<Route.AllPackages> { NotReadyYet() }
        composable<Route.Plan> { NotReadyYet() }
        composable<Route.Scanner> { NotReadyYet() }
        composable<Route.Analytics> { NotReadyYet() }
        composable<Route.Settings> { NotReadyYet() }
    }
}

@Composable
private fun NotReadyYet() = EmptyState(text = stringResource(R.string.screen_not_ready))

/** Своё ли это место — по маршруту, а не по подписи: подпись переводится, маршрут нет. */
private fun NavDestination.leadsTo(destination: Destination): Boolean =
    hierarchy.any { it.hasRoute(destination.route::class) }

private fun NavController.goTo(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

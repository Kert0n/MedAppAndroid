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
import kotlin.reflect.typeOf
import kotlin.uuid.Uuid
import androidx.navigation.toRoute
import com.kert0n.medapp.feature.medkits.MedKitFormScreen
import com.kert0n.medapp.feature.packs.MedKitContentsScreen
import com.kert0n.medapp.feature.packs.PackageAmountScreen
import com.kert0n.medapp.feature.packs.PackageFormScreen
import com.kert0n.medapp.feature.packs.PackageScreen
import com.kert0n.medapp.feature.packs.PackageTransferScreen
import com.kert0n.medapp.feature.medkits.MedKitListScreen
import com.kert0n.medapp.ui.EmptyState

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
                label = { Text(stringResource(destination.label)) }
            )
        }
    }
}

/**
 * Содержимое мест. Экранов ещё нет — за каждым стоит общее «пусто», то самое, которое потом
 * покажут настоящие экраны, когда показывать действительно нечего.
 */
@Composable
private fun MedAppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = Route.MedKits, modifier = modifier) {
        composable<Route.MedKits> {
            MedKitListScreen(
                onOpen = { navController.navigate(Route.MedKitContents(it)) },
                onAdd = { navController.navigate(Route.MedKitForm()) },
                onSearch = { navController.navigate(Route.AllMedicines) }
            )
        }
        composable<Route.Plan> { NotReadyYet() }
        composable<Route.Scanner> { NotReadyYet() }
        composable<Route.Analytics> { NotReadyYet() }
        composable<Route.Settings> { NotReadyYet() }
        composable<Route.MedKitForm>(typeMap = RouteTypes) { entry ->
            val route = entry.toRoute<Route.MedKitForm>()
            MedKitFormScreen(route.medKitId, onDone = { navController.popBackStack() })
        }
        composable<Route.MedKitContents>(typeMap = RouteTypes) { entry ->
            val route = entry.toRoute<Route.MedKitContents>()
            MedKitContentsScreen(
                medKitId = route.medKitId,
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate(Route.PackageCard(it)) },
                onAdd = { navController.navigate(Route.PackageForm(route.medKitId)) },
                onEdit = { navController.navigate(Route.MedKitForm(route.medKitId)) }
            )
        }
        composable<Route.PackageForm>(typeMap = RouteTypes) { entry ->
            val route = entry.toRoute<Route.PackageForm>()
            PackageFormScreen(
                medKitId = route.medKitId,
                onDone = { navController.popBackStack() },
                packageId = route.packageId,
                onChangeAmount = { navController.navigate(Route.PackageAmount(it)) }
            )
        }
        composable<Route.AllMedicines> {
            MedKitContentsScreen(
                medKitId = null,
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate(Route.PackageCard(it)) },
                onAdd = { navController.navigate(Route.PackageForm()) }
            )
        }
        composable<Route.PackageCard>(typeMap = RouteTypes) { entry ->
            val route = entry.toRoute<Route.PackageCard>()
            PackageScreen(
                packageId = route.packageId,
                onBack = { navController.popBackStack() },
                onEdit = { medKitId ->
                    navController.navigate(Route.PackageForm(medKitId, route.packageId))
                },
                onChangeAmount = { navController.navigate(Route.PackageAmount(route.packageId)) },
                onTransfer = { navController.navigate(Route.PackageTransfer(route.packageId)) }
            )
        }
        composable<Route.PackageAmount>(typeMap = RouteTypes) { entry ->
            val route = entry.toRoute<Route.PackageAmount>()
            PackageAmountScreen(route.packageId, onDone = { navController.popBackStack() })
        }
        composable<Route.PackageTransfer>(typeMap = RouteTypes) { entry ->
            val route = entry.toRoute<Route.PackageTransfer>()
            PackageTransferScreen(route.packageId, onDone = { navController.popBackStack() })
        }
    }
}

/** Чем маршруты возят идентификаторы: один набор на всё приложение. */
private val RouteTypes = mapOf(
    typeOf<Uuid>() to UuidNavType,
    typeOf<Uuid?>() to UuidOrNoneNavType
)

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

package com.kert0n.medapp.app.navigation

import com.kert0n.medapp.presentation.RouteArguments
import java.io.File
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * В маршруте едут только идентификаторы, дата и режим — ни объектов, ни ключей приглашения
 * (PLAN H3, G3). Маршрут переживает смерть процесса: объект в нём к моменту восстановления был бы
 * устаревшей копией того, что лежит в базе, а ключ приглашения оказался бы в логах навигации.
 *
 * Список маршрутов здесь перечислен руками — иначе нельзя: `Route` это интерфейс, и
 * `serializer<Route>()` отдаёт открытый полиморфный сериализатор, который о случаях ничего не
 * знает. Поэтому список стережёт [everyDeclaredRouteIsChecked]: он читает сам `Route.kt` и
 * падает, назвав маршрут, который объявили и забыли проверить.
 *
 * Красная проверка: дать любому маршруту поле доменного типа или поле с «key» в имени — тест
 * краснеет с именем этого поля; объявить маршрут и не дописать его сюда — краснеет сторож.
 */
class RouteTest {

    /** Что разрешено возить: примитивы, из которых собираются идентификатор, дата и режим. */
    private val allowedKinds = setOf("STRING", "INT", "LONG", "BOOLEAN", "ENUM")

    private val routes: List<SerialDescriptor> = listOf(
        serializer<Route.MedKits>(),
        serializer<Route.Plan>(),
        serializer<Route.Scanner>(),
        serializer<Route.Analytics>(),
        serializer<Route.Settings>(),
        serializer<Route.MedKitForm>(),
        serializer<Route.MedKitContents>(),
        serializer<Route.AllPackages>(),
        serializer<Route.Package>(),
        serializer<Route.PackageForm>(),
        serializer<Route.PackageAmount>(),
        serializer<Route.PackageTransfer>()
    ).map { it.descriptor }

    /** Сторож списка: что объявлено в `Route`, то и проверено. */
    @Test
    fun everyDeclaredRouteIsChecked() {
        val source = listOf(
            File("src/main/java/com/kert0n/medapp/app/navigation/Route.kt"),
            File("app/src/main/java/com/kert0n/medapp/app/navigation/Route.kt")
        ).firstOrNull { it.isFile } ?: error("Route.kt не найден: проверка прошла бы впустую")

        val declared = DECLARATION.findAll(source.readText()).map { it.groupValues[1] }.toSortedSet()
        val checked = routes.map { it.serialName.substringAfterLast('.') }.toSortedSet()

        assertTrue("маршрутов объявлено ${declared.size} — файл не тот", declared.size >= 12)
        assertEquals("объявлено и не проверено", declared, checked)
    }

    @Test
    fun routesCarryOnlyPlainIdentifiers() {
        for (descriptor in routes) {
            for (index in 0 until descriptor.elementsCount) {
                val field = descriptor.getElementName(index)
                val kind = descriptor.getElementDescriptor(index).kind.toString().substringAfterLast('.')
                assertTrue(
                    "${descriptor.serialName}.$field везёт $kind — в маршруте едут идентификаторы",
                    kind in allowedKinds
                )
            }
        }
    }

    /** Ключ приглашения в маршруте оказался бы в логах навигации (PLAN G3). */
    @Test
    fun noRouteCarriesAnInvitationKey() {
        for (descriptor in routes) {
            for (index in 0 until descriptor.elementsCount) {
                val field = descriptor.getElementName(index).lowercase()
                assertTrue("${descriptor.serialName}.$field возит ключ", "key" !in field)
            }
        }
    }

    /**
     * Имена полей маршрута — те же, под которыми `ViewModel` достаёт их из `SavedStateHandle`.
     * Представление маршрутов не видит (граница H1) и знает только имена, так что разъехаться они
     * могли бы молча: переименованное поле оставило бы форму без своего места и открыло бы её
     * пустой.
     *
     * Красная проверка: переименовать поле любого маршрута — тест краснеет с его именем.
     */
    @Test
    fun routeFieldsAreNamedAsThePresentationLooksThemUp() {
        val known = setOf(RouteArguments.MED_KIT_ID, RouteArguments.PACKAGE_ID)
        for (descriptor in routes) {
            for (index in 0 until descriptor.elementsCount) {
                val field = descriptor.getElementName(index)
                assertTrue("${descriptor.serialName}.$field не назван в RouteArguments", field in known)
            }
        }
    }

    /** Пять мест нижней навигации, и каждое названо своим маршрутом. */
    @Test
    fun everyDestinationHasItsOwnRoute() {
        val routesOfTabs = Destination.entries.map { it.route }

        assertEquals(routesOfTabs.size, routesOfTabs.toSet().size)
        assertEquals(5, routesOfTabs.size)
    }

    private companion object {
        val DECLARATION = Regex("""data (?:class|object) (\w+) *[(:]""")
    }
}

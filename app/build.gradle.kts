import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/**
 * Параметры сборки приходят из local.properties или из переменных окружения CI и в
 * репозиторий не попадают (G1). Регистрационный токен неизбежно присутствует в APK —
 * приложение само отправляет его при регистрации, — поэтому достижимое требование
 * именно такое: токена нет в истории git и в логах.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secretOrNull(key: String): String? =
    (localProperties.getProperty(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

/** Значение по умолчанию допустимо только в debug: release без настроенного токена не собирается. */
fun debugSecret(key: String, fallback: String): String = secretOrNull(key) ?: run {
    logger.warn("$key не задан: debug-сборка взяла значение по умолчанию. Настоящее значение задаётся в local.properties или в окружении CI.")
    fallback
}

/**
 * Release не должен уезжать с публичным dev-значением токена. Проверка отложена до самой
 * сборки release, а не сделана на конфигурации: иначе отсутствие секрета роняло бы и
 * debug-сборку, и разбор проекта в IDE.
 */
val secretsMissingForRelease = listOf("MEDAPP_REGISTRATION_TOKEN").filter { secretOrNull(it) == null }

val verifyReleaseSecrets = tasks.register("verifyReleaseSecrets") {
    group = "verification"
    description = "Не даёт собрать release без настроенных секретов (G1)."
    doLast {
        if (secretsMissingForRelease.isNotEmpty()) {
            throw GradleException(
                "Release-сборка требует секреты: ${secretsMissingForRelease.joinToString()}. " +
                    "Задайте их в local.properties или в окружении CI. " +
                    "Значение по умолчанию существует только для debug."
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(verifyReleaseSecrets) }

android {
    namespace = "com.kert0n.medapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kert0n.medapp"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.kert0n.medapp.HiltTestRunner"

        // Проба контракта ходит в боевой сервер только по явному -Pprobe (AGENTS «Связь с
        // сервером»). Адрес и пробные учётки приходят аргументами инструментации из
        // local.properties: в APK и в git их нет, а без -Pprobe проба пропускается.
        // Проверка регистрации — отдельно, по -PprobeRegistration: каждый её прогон заводит
        // на сервере новую учётку.
        if (project.hasProperty("probe") || project.hasProperty("probeRegistration")) {
            testInstrumentationRunnerArguments["probeBaseUrl"] =
                secretOrNull("MEDAPP_BASE_URL") ?: "https://medapp.ru.net"
        }
        if (project.hasProperty("probeRegistration")) {
            testInstrumentationRunnerArguments["probeRegistration"] = "true"
        }
        if (project.hasProperty("probe")) {
            for (user in listOf("A", "B")) {
                testInstrumentationRunnerArguments["probeLogin$user"] =
                    secretOrNull("MEDAPP_PROBE_${user}_LOGIN").orEmpty()
                testInstrumentationRunnerArguments["probeKey$user"] =
                    secretOrNull("MEDAPP_PROBE_${user}_KEY").orEmpty()
            }
        }

        // Адреса секретами не являются: у них есть законное значение по умолчанию.
        buildConfigField(
            "String",
            "BASE_URL",
            "\"${secretOrNull("MEDAPP_BASE_URL") ?: "https://medapp.ru.net"}\""
        )
        buildConfigField(
            "String",
            "CRPT_BASE_URL",
            "\"${secretOrNull("MEDAPP_CRPT_BASE_URL") ?: "https://mobile.api.crpt.ru"}\""
        )
        // Сервер срок приглашения не отдаёт, а задаёт настройкой `medkit.share.termInMinutes`:
        // клиент показывает оценку по этому числу (PLAN B6).
        buildConfigField("long", "INVITATION_TERM_MINUTES", "60L")
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "REGISTRATION_TOKEN",
                "\"${debugSecret("MEDAPP_REGISTRATION_TOKEN", "dev-secret")}\""
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Пусто, если секрет не задан; собрать release с таким значением не даст
            // verifyReleaseSecrets. Публичного dev-значения здесь нет и быть не должно.
            buildConfigField(
                "String",
                "REGISTRATION_TOKEN",
                "\"${secretOrNull("MEDAPP_REGISTRATION_TOKEN").orEmpty()}\""
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    /**
     * Фикстуры домена нужны обоим уровням: значения и сущности одни и те же, а база
     * проверяется в androidTest (PLAN J1). Второй набор строителей разошёлся бы с первым.
     */
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }
}

/**
 * Схема лежит в репозитории, а не только внутри APK: без прошлой версии рядом миграцию нечем
 * проверить, а `MigrationTestHelper` берёт её из assets инструментальных тестов, куда её кладёт
 * этот же плагин.
 */
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    // `hiltViewModel` переехал сюда из hilt-navigation-compose: зависимость объявлена явно,
    // потому что транзитивная однажды уже исчезла (значки).
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // База проверяется в androidTest (PLAN J1): DAO, транзакции, ограничения и миграции идут
    // против настоящего SQLite, а не против его подобия на JVM.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

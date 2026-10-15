import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Category

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.bcv)
}

@OptIn(ExperimentalBCVApi::class)
apiValidation {
    // Exclude modules without a stable public API or test-only modules
    ignoredPackages.add("io.github.slavikjunior.kache.core.internal")

    // KLib ABI validation covers the iOS targets. It is disabled by default, which
    // makes `klibApiCheck` report SKIPPED and leaves no golden ABI file behind, so
    // any change to the public API of an iOS target would pass unnoticed.
    klib {
        enabled = true
    }
}

/**
 * Компоненты, для которых Gradle уже записал хотя бы один артефакт.
 *
 * Читается из самого файла верификации, а не из списка в скрипте: набор растёт при
 * каждом обновлении зависимости, и любая копия в коде рано или поздно устареет.
 */
val verifiedComponents: List<Triple<String, String, String>> by lazy {
    val file = file("gradle/verification-metadata.xml")
    if (!file.exists()) return@lazy emptyList()
    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)
    val nodes = document.getElementsByTagName("component")
    (0 until nodes.length).mapNotNull { index ->
        val element = nodes.item(index)
        Triple(
            element.attributes.getNamedItem("group").nodeValue,
            element.attributes.getNamedItem("name").nodeValue,
            element.attributes.getNamedItem("version").nodeValue,
        )
    }
}

/**
 * Резолвит документационные варианты (`sources`, `javadoc`, `samples-sources`) для всех
 * компонент из `verification-metadata.xml`.
 *
 * Зачем это нужно. `--write-verification-metadata` записывает только то, что реально
 * разрешил CLI-процесс. Android Studio при sync строит модель всех вариантов проекта,
 * включая конфигурации, которых CLI-граф не касается: у `:sample-android` нет ни одного
 * androidTest-исходника, но IDE всё равно резолвит `debugAndroidTestCompileClasspath`.
 * Такой резолв тянет документационные варианты зависимостей, и каждый из них падает на
 * верификации отдельным sync — волнами по classifier'ам: сначала `sources`, потом
 * `javadoc`, потом KMP-специфичные `-common-sources` и `-samples-sources`.
 *
 * Задача закрывает их одним прогоном. Использование:
 *
 * ```
 * ./gradlew --write-verification-metadata sha256 recordIdeIndexingArtifacts
 * ```
 *
 * Gradle сам скачивает артефакты из объявленных репозиториев и записывает их
 * SHA-256 — доверять нечего ровно в том смысле, в котором не доверяют Maven Central.
 * Без флага задача ничего не пишет и служит проверкой: под strict-верификацией она
 * должна завершиться успехом, иначе синк в IDE снова упадёт.
 */
tasks.register("recordIdeIndexingArtifacts") {
    group = "verification"
    description = "Resolves documentation variants of every verified component so the IDE can sync."

    // Задача по определению должна резолвить: up-to-date-проверка здесь бессмысленна,
    // проверяться может только результат резолва, а он и есть её работа.
    outputs.upToDateWhen { false }

    // Резолв detached-конфигураций на этапе выполнения несовместим с configuration
    // cache: сам список конфигураций зависит от файла верификации.
    notCompatibleWithConfigurationCache("Resolves detached configurations at execution time")

    val components = verifiedComponents

    doLast {
        val category = objects.named(Category::class.java, "documentation")

        fun documentationConfiguration(coordinates: String) =
            configurations.detachedConfiguration(dependencies.create(coordinates)).apply {
                // Транзитивность выключена: иначе detached-конфигурация тянет весь граф
                // модуля и записывает несвязанные бинарники (guava 18, protobuf-java 3.19).
                isTransitive = false
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, category)
                }
            }

        /**
         * Публикует ли модуль документационный вариант. Смотрим только граф
         * (`resolutionResult`), а не файлы: скачивание даже отбракованного бинарника
         * `--write-verification-metadata` записал бы в metadata. Корневой компонент
         * конфигурации не учитываем, он всегда несёт запрошенный Category.
         */
        fun publishesDocumentation(configuration: Configuration): Boolean =
            configuration.incoming.resolutionResult.allComponents
                .filter { it.id is ModuleComponentIdentifier }
                .flatMap { it.variants }
                .any { it.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == "documentation" }

        // Разведка. Ошибка здесь — не повод молча пропустить компоненту: непроверенный граф
        // означает, что sync снова упадёт, поэтому собираем все такие сбои и валим задачу.
        val discoverable = mutableListOf<Triple<String, String, String>>()
        val discoveryFailures = mutableListOf<String>()
        components.forEach { (group, name, version) ->
            try {
                if (publishesDocumentation(documentationConfiguration("$group:$name:$version"))) {
                    discoverable += Triple(group, name, version)
                }
            } catch (failure: Exception) {
                discoveryFailures += "$group:$name:$version: ${failure.message?.lineSequence()?.firstOrNull()}"
            }
        }
        if (discoveryFailures.isNotEmpty()) {
            throw GradleException(
                "Не удалось определить документационные варианты для ${discoveryFailures.size} компонент:\n" +
                    discoveryFailures.joinToString("\n") { "  - $it" },
            )
        }

        // Скачивание: только для модулей, которые документационный вариант действительно публикуют.
        // Строгое представление без lenient: непроверенный артефакт обязан валить задачу.
        var files = 0
        discoverable.forEach { (group, name, version) ->
            val resolved = documentationConfiguration("$group:$name:$version")
                .incoming.artifactView { lenient(false) }.artifacts.artifactFiles.files
            files += resolved.size
            resolved.forEach { logger.lifecycle("  ${it.name}  ($group:$name:$version)") }
        }

        logger.lifecycle(
            "Документационные варианты: ${components.size} компонент, " +
                "${discoverable.size} публикуют их, $files файлов разрезолвлено.",
        )
    }
}

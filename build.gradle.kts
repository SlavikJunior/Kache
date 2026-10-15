import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType

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
val recordedComponents: List<Triple<String, String, String>> by lazy {
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
 * Библиотеки, которые сам дистрибутив Gradle кладёт в `lib/`, с координатами из его же
 * `*.properties`-файлов.
 *
 * Это не список и не хардкод одной координаты. Рядом с каждым внешним jar дистрибутив
 * кладёт `<name>.properties` с полями `alias.group`, `alias.name`, `alias.version` (и
 * `jarFile`), поэтому координаты берутся у самого Gradle, а не угадываются. Gradle 9.4.1
 * шёл с Groovy 4.0.29, Gradle 9.6.1 — с 4.0.32 и `junit-bom:5.12.2`.
 * `verification-metadata.xml` помнит версии предыдущего дистрибутива, поэтому IDE получает
 * sources новой библиотеки, которой в метаданных ещё нет, и падает на Sync. CLI этого не
 * видит: `check` не резолвит документационные варианты встроенных библиотек Gradle.
 * Файлы без `alias.group` (собственные `gradle-*` модули) пропускаются: это не внешние
 * Maven-компоненты, и документации в репозитории для них нет.
 */
val embeddedComponents: List<Triple<String, String, String>> by lazy {
    val lib = gradle.gradleHomeDir?.resolve("lib")?.takeIf { it.isDirectory } ?: return@lazy emptyList()
    lib.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "properties" }
        .mapNotNull { alias ->
            val properties = java.util.Properties()
            alias.inputStream().use(properties::load)
            val group = properties.getProperty("alias.group")?.takeIf { it.isNotBlank() }
            val name = properties.getProperty("alias.name")?.takeIf { it.isNotBlank() }
            val version = properties.getProperty("alias.version")?.takeIf { it.isNotBlank() }
            if (group == null || name == null || version == null) null else Triple(group, name, version)
        }
        .distinct()
        .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
}

/**
 * Виды документации, которые IDE запрашивает для индексов. Это стандартные значения
 * атрибута `org.gradle.docstype` самого Gradle, а не список имён файлов. Вариант `sources`
 * несёт и KMP-`-common-sources`, и AndroidX-`-samples-sources`, и плагинные
 * `-gradle813-sources` — это файлы того же варианта, а не отдельный `docstype`.
 */
val documentationKinds: List<String> = listOf(DocsType.SOURCES, DocsType.JAVADOC)

/** Всё, что задача должна покрыть: записанные компоненты плюс библиотеки дистрибутива. */
val verifiedComponents: List<Triple<String, String, String>> by lazy {
    (recordedComponents + embeddedComponents).distinct()
}

/**
 * Резолвит документационные варианты (`sources`, `javadoc` и файлы `-common-sources` /
 * `-samples-sources` этих вариантов) для всех компонент из `verification-metadata.xml`, а
 * также для библиотек, которые положил в `lib/` сам дистрибутив Gradle.
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
        val documentation = objects.named(Category::class.java, "documentation")

        /**
         * Выбрал ли Gradle именно документационный вариант модуля нужного вида. Смотрим на
         * граф (`resolutionResult`), а не на файлы: скачивание даже отбракованного файла
         * `--write-verification-metadata` записал бы в metadata. Так попадали `gson`,
         * `jsoup`, `commons-compress`: у POM-модулей без документации Gradle выбирает
         * основной jar, и без этой проверки он уходил бы в metadata.
         */
        fun selectsDocumentation(configuration: Configuration, kind: String): Boolean =
            configuration.incoming.resolutionResult.allComponents
                .filter { it.id is ModuleComponentIdentifier }
                .flatMap { it.variants }
                .any { variant ->
                    variant.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == "documentation" &&
                        variant.attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE)?.name == kind
                }

        fun configurationFor(coordinates: String, kind: String): Configuration =
            configurations.detachedConfiguration(dependencies.create(coordinates)).apply {
                isTransitive = false
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, documentation)
                    attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType::class.java, kind))
                }
            }

        // Разведка: граф для каждой пары «компонента–вид». Файлы здесь не скачиваем.
        // Сбой графа — не повод молча пропустить модуль: собираем и валим задачу.
        val discoverable = mutableListOf<Pair<Triple<String, String, String>, String>>()
        val discoveryFailures = mutableListOf<String>()
        components.forEach { component ->
            val (group, name, version) = component
            documentationKinds.forEach { kind ->
                try {
                    if (selectsDocumentation(configurationFor("$group:$name:$version", kind), kind)) {
                        discoverable += component to kind
                    }
                } catch (failure: Exception) {
                    discoveryFailures += "$group:$name:$version [$kind]: ${failure.message?.lineSequence()?.firstOrNull()}"
                }
            }
        }
        if (discoveryFailures.isNotEmpty()) {
            throw GradleException(
                "Не удалось определить документационные варианты для ${discoveryFailures.size} случаев:\n" +
                    discoveryFailures.joinToString("\n") { "  - $it" },
            )
        }

        // Скачивание: строго, без перехвата. Любой непроверенный файл валит задачу.
        var files = 0
        discoverable.forEach { (component, kind) ->
            val (group, name, version) = component
            val configuration = configurationFor("$group:$name:$version", kind)
            val resolved = configuration.incoming.artifactView { lenient(false) }.artifacts.artifactFiles.files
            files += resolved.size
            resolved.forEach { logger.lifecycle("  ${it.name}  ($group:$name:$version, $kind)") }
        }

        logger.lifecycle(
            "Документационные варианты: ${components.size} компонент, " +
                "${discoverable.size} пар «компонента–вид» публикуют документацию, $files файлов разрезолвлено.",
        )
    }
}

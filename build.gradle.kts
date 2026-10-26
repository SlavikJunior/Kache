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

    // The sample is an app, not a published artifact: its API is not a contract, and a
    // golden dump would have to be regenerated for every UI change.
    ignoredProjects.add("sample")

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
 * Резолвит документационные варианты компонент из `verification-metadata.xml`, а также
 * библиотек, которые положил в `lib/` сам дистрибутив Gradle.
 *
 * Сами документационные файлы в файле верификации не пинятся: они доверены по имени через
 * `<trusted-artifacts>`, потому что в compile/runtime classpath не попадают. Но запрос
 * документации заставляет Gradle скачать дескриптор модуля (`.module`/`.pom`), а дескрипторы
 * пинятся. Поэтому задача нужна ровно для одного события — обновления wrapper'а: дистрибутив
 * кладёт свои runtime-библиотеки в `lib/`, их версии меняются, и IDE резолвит их алиасы из
 * репозитория, хотя ни одна CLI-задача этого не делает.
 *
 * Использование (после обновления Gradle, один раз):
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

/**
 * Проверяет состав локальной публикации, а не только факт её успеха.
 *
 * `publishAllPublicationsToLocalRepository` завершается успешно и при отсутствующем
 * `-sources.jar`: Gradle не считает это ошибкой, а Maven Central считает. Поэтому после
 * публикации разбирается сам репозиторий и валится, если у публикации нет POM, module-
 * дескриптора, непустых sources/javadoc, а у android/native-вариантов — AAR/klib.
 *
 * Набор артефактов задан явно, а не выведен из имён: так пропавший вариант (например,
 * `cache-core-iosx64`) — это ошибка, а не молчание. Новый таргет обязан быть добавлен
 * сюда вместе с публикацией.
 */
val expectedPublishedArtifacts: Map<String, String?> = linkedMapOf(
    // Корневые metadata-модули: у них нет ни AAR, ни klib.
    "cache-core" to null,
    "cache-core-jvm" to null,
    "cache-core-android" to "aar",
    "cache-core-iosarm64" to "klib",
    "cache-core-iossimulatorarm64" to "klib",
    "cache-core-iosx64" to "klib",
    "cache-storage" to null,
    "cache-storage-jvm" to null,
    "cache-storage-android" to "aar",
    // iOS-вариант несёт только сериализаторы: файлового движка на iOS нет.
    "cache-storage-iosarm64" to "klib",
    "cache-storage-iossimulatorarm64" to "klib",
    "cache-store-room" to null,
    "cache-store-room-jvm" to null,
    "cache-store-room-android" to "aar",
    "cache-store-room-iosarm64" to "klib",
    "cache-store-room-iossimulatorarm64" to "klib",
    // android-only KMP-модуль: metadata-модуль назван `cache-android`, android-вариант —
    // `cache-android-android`, поэтому правило «имя оканчивается на -android» здесь не
    // работает и вариант назван явно.
    "cache-android" to null,
    "cache-android-android" to "aar",
)

val verifyLocalPublication by tasks.registering {
    group = "verification"
    description = "Проверяет, что каждая локальная публикация полна (pom, module, sources, javadoc, aar/klib)."

    dependsOn(
        ":cache-core:publishAllPublicationsToLocalRepository",
        ":cache-storage:publishAllPublicationsToLocalRepository",
        ":cache-store-room:publishAllPublicationsToLocalRepository",
        ":cache-android:publishAllPublicationsToLocalRepository",
    )

    val repository = layout.buildDirectory.dir("repo")
    val publishedVersion = providers.gradleProperty("VERSION_NAME")
    val groupPath = providers.gradleProperty("GROUP").map { it.replace('.', '/') }
    val expected = expectedPublishedArtifacts

    doLast {
        val version = publishedVersion.get()
        val groupDir = repository.get().asFile.resolve(groupPath.get())
        if (!groupDir.isDirectory) {
            throw GradleException("Локальный репозиторий не найден: ${groupDir.absolutePath}")
        }

        val violations = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val actual = groupDir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()

        (expected.keys - actual).sorted().forEach { violations += "$it: публикация отсутствует" }
        (actual - expected.keys).sorted().forEach { violations += "$it: неожиданная публикация, добавьте её в expectedPublishedArtifacts" }

        expected.forEach { (artifact, extraExtension) ->
            val versionDir = groupDir.resolve(artifact).resolve(version)
            if (!versionDir.isDirectory) return@forEach

            val base = "$artifact-$version"

            fun require(condition: Boolean, message: String) {
                if (!condition) violations += "$artifact: $message"
            }

            require(versionDir.resolve("$base.pom").isFile, "нет $base.pom")
            require(versionDir.resolve("$base.module").isFile, "нет $base.module")

            val sources = versionDir.resolve("$base-sources.jar")
            require(sources.isFile && sources.length() > 0, "нет или пуст $base-sources.jar")

            val javadoc = versionDir.resolve("$base-javadoc.jar")
            require(javadoc.isFile && javadoc.length() > 0, "нет или пуст $base-javadoc.jar")

            if (extraExtension != null) {
                val binary = versionDir.resolve("$base.$extraExtension")
                require(binary.isFile && binary.length() > 0, "нет или пуст $base.$extraExtension")
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Локальная публикация неполна:\n" + violations.joinToString("\n") { "  - $it" },
            )
        }

        logger.lifecycle(
            "Локальная публикация проверена: ${expected.size} артефактов, " +
                "у каждого есть pom, module, sources и непустой javadoc.",
        )
    }
}

/**
 * Checks that the test counts written in the documentation match the tests that actually ran.
 *
 * The counts drifted three times in this project — and once `AGENTS.md` disagreed with itself —
 * because a number changed in one file and the prose was updated by hand in another. A number
 * nobody machine-checks is a number that is eventually wrong.
 *
 * The task reads whatever results exist under each module's `build/test-results` and refuses to pass when
 * they are missing, so "run `verifyDocCounts` on a clean tree" fails loudly instead of
 * comparing against nothing. The Android-device column is not checked: it needs an emulator,
 * which no local run guarantees.
 *
 * A cell with no results behind it is skipped and named in the output, because no single
 * machine produces the whole matrix: Linux has no iOS simulator, and macOS alone has no
 * Android host run unless `check` is invoked there too. The total is therefore verified only
 * when every cell was measured — a total derived from half a matrix is a number about nothing.
 * The authoritative full run is `./gradlew check verifyDocCounts` on macOS.
 */
val verifyDocCounts by tasks.registering {
    group = "verification"
    description = "Verifies the test counts stated in both READMEs against the test results."

    doLast {
        val root = layout.projectDirectory.asFile

        /** Module → the test task that fills each column of the matrix. */
        val matrix = linkedMapOf(
            ":cache-core" to Triple("jvmTest", "testAndroidHostTest", "iosSimulatorArm64Test"),
            ":cache-storage" to Triple("jvmTest", "testAndroidHostTest", "iosSimulatorArm64Test"),
            ":cache-store-room" to Triple("jvmTest", "testAndroidHostTest", "iosSimulatorArm64Test"),
            ":cache-android" to Triple(null, "testAndroidHostTest", null),
            ":sample" to Triple("desktopTest", "testAndroidHostTest", "iosSimulatorArm64Test"),
        )

        fun count(module: String, task: String?): Int? {
            if (task == null) return null
            val dir = File(root, "${module.removePrefix(":")}/build/test-results/$task")
            val files = dir.listFiles { file -> file.extension == "xml" }.orEmpty()
            if (files.isEmpty()) return null
            return files.sumOf { file ->
                Regex("""tests="(\d+)"""").find(file.readText())?.groupValues?.get(1)?.toInt() ?: 0
            }
        }

        val measured = matrix.mapValues { (module, tasks) ->
            Triple(count(module, tasks.first), count(module, tasks.second), count(module, tasks.third))
        }
        val missing = measured.filterValues { it.first == null && it.second == null && it.third == null }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Нет результатов тестов для ${missing.joinToString()}. " +
                    "Сначала выполните `./gradlew check` (или соответствующие test-таски).",
            )
        }

        val violations = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // README.md: the matrix table is the single place the per-module numbers live. It used
        // to live in a working note outside the repository, so nothing a reader of the
        // published docs could check was covered by it.
        val table = File(root, "README.md")
        if (!table.isFile) {
            violations += "README.md отсутствует"
        } else {
            // Only the coverage table: the README has module tables for platforms and for
            // dependencies too, and their first column is also a module name, so the header
            // has to match the coverage one in full.
            val lines = table.readLines()
            val header = lines.indexOfFirst { it.startsWith("| Module | JVM | Android host | iOS simulator |") }
            if (header < 0) {
                violations += "README.md: не найдена таблица покрытия тестами"
            }
            val rows = if (header < 0) {
                emptyList()
            } else {
                // +2: the header, then the `|---|` separator row.
                lines.drop(header + 2).takeWhile { it.startsWith("|") }.filter { it.startsWith("| `:") }
            }
            val seen = mutableSetOf<String>()
            rows.forEach { row ->
                val cells = row.split('|').map(String::trim)
                val module = cells[1].trim('`')
                val expected = listOf(cells[2], cells[3], cells[4])
                val actual = measured[module] ?: run {
                    violations += "$module указан в таблице, но не измеряется"
                    return@forEach
                }
                seen += module
                expected.forEachIndexed { index, cell ->
                    val value = cell.removeSuffix(" (desktop)").takeUnless { it == "—" }?.toInt()
                        ?: return@forEachIndexed
                    val measuredValue = actual.toList()[index] ?: run {
                        skipped += "$module/${listOf("JVM", "Android host", "iOS sim")[index]}"
                        return@forEachIndexed
                    }
                    if (value != measuredValue) {
                        violations += "$module, колонка ${listOf("JVM", "Android host", "iOS sim")[index]}: " +
                            "в таблице $value, реально $measuredValue"
                    }
                }
            }
            (measured.keys - seen).forEach { violations += "$it не указан в таблице README.md" }
        }

        val total = measured.values.sumOf { (a, b, c) -> (a ?: 0) + (b ?: 0) + (c ?: 0) }
        if (skipped.isNotEmpty()) {
            logger.lifecycle(
                "Пропущено (нет результатов на этой машине): ${skipped.joinToString()}. " +
                    "Итоговая сумма не проверяется — полная матрица требует macOS: " +
                    "`./gradlew check verifyDocCounts`.",
            )
        }
        listOf("README.md", "README.ru.md").forEach { name ->
            val file = File(root, name)
            if (!file.isFile) return@forEach
            val text = file.readText()
            if (skipped.isNotEmpty()) return@forEach
            val badge = Regex("""tests-(\d+)%20green""").find(text)?.groupValues?.get(1)?.toInt()
            if (badge != null && badge != total) {
                violations += "$name: бейдж $badge, реально $total"
            }
            Regex("""\b(\d+)\s+(?:tests|теста|тестов) (?:in|в) the standard matrix|standard matrix""")
            val prose = Regex("""(\d+)\s+(?:tests|теста|тестов)\s+(?:in the standard matrix|в стандартной матрице)""")
                .find(text)?.groupValues?.get(1)?.toInt()
            if (prose != null && prose != total) {
                violations += "$name: в тексте $prose, реально $total"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Счётчики тестов в документации расходятся с кодом:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\nЗначения измерены по XML-отчётам: $total в матрице.",
            )
        }
        logger.lifecycle(
            if (skipped.isEmpty()) {
                "Счётчики тестов совпадают с документацией: $total в матрице."
            } else {
                "Счётчики тестов совпадают с документацией в проверенных ячейках ($total на этой машине)."
            },
        )
    }
}

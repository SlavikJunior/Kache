package io.github.slavikjunior.kache.android

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals

/**
 * The golden API file for the Android target, which BCV cannot produce.
 *
 * `:cache-android` publishes an AAR, and BCV hooks a KMP Android target only when its
 * compilation is named `release` while the AGP KMP plugin names it `main`; upgrades (checked
 * up to 0.18.1) do not change that. `apiCheck` therefore covers every published artifact
 * except this one, and an accidental rename, a dropped overload or a newly exposed internal
 * class would ship silently.
 *
 * The surface is rendered from the classes on the test classpath — the same bytecode the AAR
 * is built from — and compared with `cache-android-api.txt`. Regenerating is deliberate:
 *
 * ```
 * KACHE_UPDATE_API_SURFACE=1 ./gradlew :cache-android:testAndroidHostTest
 * ```
 *
 * then read the diff. A regenerated file is a reviewed change, not a formality: it is the
 * module's public promise in text form.
 *
 * Two normalisations keep the file readable and stable across Kotlin versions:
 * - a `suspend` function is rendered as `suspend fun` with its trailing `Continuation`
 *   parameter removed, because that parameter is a compiler detail, not the caller's contract;
 * - the JVM mangles a function whose signature mentions a value class (`l2Cache-2GCC57s`), so
 *   the suffix is stripped — the parameter types next to it carry the real information.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidApiSurfaceTest {

    @Test
    fun `the published Android surface matches its golden file`() {
        val actual = AndroidApiSurface.render()
        val golden = File(GOLDEN_PATH)

        if (System.getenv("KACHE_UPDATE_API_SURFACE") == "1") {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            return
        }

        assertEquals(
            golden.readText(),
            actual,
            "The Android API surface changed. If this is intended, regenerate the golden file with " +
                "KACHE_UPDATE_API_SURFACE=1 and review the diff; otherwise the change is breaking.",
        )
    }

    private companion object {
        const val GOLDEN_PATH = "src/androidHostTest/resources/cache-android-api.txt"
    }
}

/**
 * Renders the module's public surface as a sorted, deterministic text.
 *
 * Only declarations the module itself writes: `declaredMethods`/`declaredFields` skip anything
 * inherited, so a supertype's members never appear under a subtype. Synthetic members (bridges,
 * `DefaultImpls` shims) are skipped because the compiler emits them, not the author.
 */
internal object AndroidApiSurface {

    private const val PACKAGE = "io.github.slavikjunior.kache.android"

    fun render(): String {
        val classNames = classesInPackage()
        return buildString {
            appendLine("# Public API of :cache-android, generated from the compiled Android classes.")
            appendLine("# Regenerate with KACHE_UPDATE_API_SURFACE=1 ./gradlew :cache-android:testAndroidHostTest")
            appendLine("# suspend functions are rendered without their Continuation; a mangled name is un-mangled.")
            appendLine("# Class type parameters are kept; a method's parameter types are erased, as the JVM sees them.")
            appendLine("# `final` and `abstract` show whether a member may be overridden, which is part of the promise.")
            classNames.forEach { name ->
                val type = Class.forName(name)
                if (!Modifier.isPublic(type.modifiers)) return@forEach
                appendLine()
                appendLine(describe(type))
            }
        }
    }

    /**
     * Class names in the module's package, read from the production output directory.
     *
     * The build hands that directory over as a system property, because the classpath cannot
     * answer the question: host-test classes live in the same package and, on this target,
     * report the same code source, so a classpath scan renders the tests as part of the API.
     * Failing loudly when the property is missing is deliberate — a silent fallback would
     * produce an empty golden file that always matches.
     */
    private fun classesInPackage(): List<String> {
        val classesDir = System.getProperty("kache.apiSurfaceClassesDir")
            ?: error("kache.apiSurfaceClassesDir is not set; the build script wires it for this test")
        val packageDir = File(classesDir, PACKAGE.replace('.', '/'))

        check(packageDir.isDirectory) {
            "$packageDir does not exist: the production classes moved, so the API surface cannot be read"
        }

        return packageDir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".class") && !it.contains('$') }
            .map { "$PACKAGE.${it.removeSuffix(".class")}" }
            .sorted()
    }

    private fun describe(type: Class<*>): String = buildString {
        append(kindOf(type)).append(' ').append(type.name)
        if (type.typeParameters.isNotEmpty()) {
            append(type.typeParameters.joinToString(", ", prefix = "<", postfix = ">") { it.name })
        }
        type.interfaces.map { it.name }.sorted().forEach { append(", ").append(it) }
        type.superclass?.takeUnless { it == Any::class.java }?.let { append(" extends ").append(it.name) }
        appendLine()

        type.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .sortedBy { it.signature() }
            .forEach { appendLine("  ctor(${it.parameterTypes.joinToString(", ") { parameter -> parameter.typeName }})") }

        type.declaredFields
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .sortedBy { it.name }
            .forEach { field ->
                val constant = if (Modifier.isStatic(field.modifiers)) "const " else ""
                appendLine("  ${constant}val ${field.name}: ${field.type.typeName}")
            }

        type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .sortedWith(compareBy({ unMangle(it.name) }, { it.signature() }))
            .forEach { appendLine("  ${it.render()}") }
    }

    private fun kindOf(type: Class<*>): String = when {
        type.isInterface -> "interface"
        Modifier.isAbstract(type.modifiers) -> "abstract class"
        else -> "class"
    }

    private fun Method.render(): String {
        val suspend = isSuspend()
        val parameters = parameterTypes
            .let { if (suspend) it.dropLast(1) else it.toList() }
            .joinToString(", ") { parameter -> parameter.typeName }
        val returnType = if (suspend) {
            // A suspend function's declared return type is `Object` in bytecode; its real
            // return type is erased behind the Continuation, so `Any?` is the honest rendering.
            "Any?"
        } else {
            this.returnType.typeName
        }
        val kind = when {
            Modifier.isAbstract(modifiers) -> "abstract fun"
            suspend -> "suspend fun"
            Modifier.isFinal(modifiers) -> "final fun"
            else -> "fun"
        }
        return "$kind ${unMangle(name)}($parameters): $returnType"
    }

    private fun Method.isSuspend(): Boolean =
        parameterTypes.lastOrNull() == Continuation::class.java && returnType == Any::class.java

    private fun Constructor<*>.signature(): String = parameterTypes.joinToString(",") { it.typeName }

    private fun Method.signature(): String = parameterTypes.joinToString(",") { it.typeName } + "->" + returnType.typeName

    /** `l2Cache-2GCC57s` → `l2Cache`: the suffix is a hash of the value-class signature. */
    private fun unMangle(name: String): String = name.substringBefore('-')

}

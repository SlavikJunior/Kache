package io.github.slavikjunior.kache.android

import android.content.ComponentCallbacks2
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the public API of `:cache-android`.
 *
 * The module publishes an AAR, and BCV cannot produce a golden API file for an AGP Kotlin
 * Multiplatform Android target: it hooks a target only when its compilation is named
 * `release`, while the AGP KMP plugin names it `main`. Dependency upgrades (checked up to
 * BCV 0.18.1) do not change that, so the Android surface is the one artifact whose API is
 * not covered by `apiCheck`.
 *
 * This test is the mitigation. It reflects over declaration shapes — names, parameter
 * types, modifiers — so removing a function, renaming it, or changing a parameter type
 * fails here instead of shipping as a silent breaking change. Behaviour is covered by the
 * other tests in this module; this one deliberately asserts nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class PublicApiTest {

    /** Public, non-synthetic declared methods, keyed by name, as parameter-type simple-name lists. */
    private fun publicMethods(className: String): Map<String, List<String>> =
        Class.forName(className).declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .groupBy { it.name }
            .mapValues { (_, methods) ->
                methods.map { method -> method.parameterTypes.joinToString(",") { it.simpleName } }.sorted()
            }

    @Test
    fun `l2Cache keeps both published overloads and their parameter order`() {
        // A function with a value-class parameter (`Duration`) is name-mangled on the JVM
        // (`l2Cache-2GCC57s`), so the match is by prefix. The parameter types themselves
        // are not erased, which is what this assertion pins.
        val actual = Class.forName("io.github.slavikjunior.kache.android.CacheFactoryKt").declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.name.startsWith("l2Cache") }
            .map { method -> method.parameterTypes.joinToString(",") { it.simpleName } }
            .sorted()

        val expected = listOf(
            // Engine overload: ownership stays with the caller.
            "StorageEngine,KacheSerializer,Duration,RetryPolicy,TimeSource,Long,EvictionStrategy,Duration,KacheLogger",
            // Context overload: builds the file engine itself.
            "Context,KacheSerializer,Duration,String,RetryPolicy,TimeSource,Long,EvictionStrategy,Duration,KacheLogger",
        ).sorted()

        assertEquals(expected, actual, "l2Cache overloads changed")
    }

    @Test
    fun `cache defaults stay public`() {
        val facade = Class.forName("io.github.slavikjunior.kache.android.CacheFactoryKt")

        val cacheDirField = facade.declaredFields.singleOrNull { it.name == "DEFAULT_CACHE_DIR_NAME" }
            ?: fail("DEFAULT_CACHE_DIR_NAME is gone")
        assertTrue(Modifier.isPublic(cacheDirField.modifiers), "DEFAULT_CACHE_DIR_NAME must stay public")

        val ttlGetter = facade.declaredMethods.singleOrNull { it.name == "getDEFAULT_TTL" }
            ?: fail("DEFAULT_TTL is gone")
        assertTrue(Modifier.isPublic(ttlGetter.modifiers), "DEFAULT_TTL must stay public")
        // `Duration` is a value class, so its JVM getter returns the erased primitive.
        assertEquals("long", ttlGetter.returnType.simpleName)
    }

    @Test
    fun `clearWhenScopeCancelled keeps its receiver and scope`() {
        val method = publicMethods("io.github.slavikjunior.kache.android.CacheLifecycleKt")["clearWhenScopeCancelled"]

        assertEquals(listOf("KmpCache,CoroutineScope"), method)
    }

    @Test
    fun `memory pressure callbacks keep both entry points`() {
        val actual = publicMethods("io.github.slavikjunior.kache.android.CacheMemoryPressureKt")

        // `requiresImmediateCacheClear` is `internal`, but Kotlin leaves internal top-level
        // functions public in the JVM bytecode, so it shows up here too. Pinning the whole
        // set keeps a renamed or newly leaked declaration visible.
        assertEquals(
            setOf("registerCacheMemoryPressureCallbacks", "unregisterCacheMemoryPressureCallbacks", "requiresImmediateCacheClear"),
            actual.keys,
        )
        assertEquals(
            listOf("Application,CoroutineScope,KmpCache[]"),
            actual["registerCacheMemoryPressureCallbacks"],
        )
        assertEquals(
            listOf("Application,CacheMemoryPressureCallbacks"),
            actual["unregisterCacheMemoryPressureCallbacks"],
        )
    }

    @Test
    fun `cache memory pressure handle stays a public component callback`() {
        val handle = Class.forName("io.github.slavikjunior.kache.android.CacheMemoryPressureCallbacks")

        assertTrue(Modifier.isPublic(handle.modifiers), "CacheMemoryPressureCallbacks must stay public")
        assertTrue(
            ComponentCallbacks2::class.java.isAssignableFrom(handle),
            "CacheMemoryPressureCallbacks must stay a ComponentCallbacks2",
        )
        assertTrue(publicMethods(handle.name).containsKey("onTrimMemory"))
        assertTrue(publicMethods(handle.name).containsKey("onLowMemory"))
    }

    @Test
    fun `kacheable view model declares exactly the public surface consumers subclass`() {
        val viewModel = Class.forName("io.github.slavikjunior.kache.android.KacheableViewModel")

        assertTrue(Modifier.isPublic(viewModel.modifiers), "KacheableViewModel must stay public")
        assertTrue(Modifier.isAbstract(viewModel.modifiers), "KacheableViewModel must stay abstract")

        assertEquals(
            setOf("getKacheState", "load", "retry", "invalidate", "clearCache"),
            publicMethods(viewModel.name).keys,
        )
    }
}

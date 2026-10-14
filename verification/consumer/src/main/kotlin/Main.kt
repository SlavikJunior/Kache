@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import io.github.slavikjunior.kache.core.CacheOrigin
import io.github.slavikjunior.kache.core.CacheResult
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.EvictionStrategy
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.core.MutableTimeSource
import io.github.slavikjunior.kache.storage.FileStorageEngine
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class UserProfile(val id: String, val name: String)

val serializer = KotlinxJsonSerializer(UserProfile.serializer())

var failures = 0

fun check(label: String, condition: Boolean, detail: String = "") {
    if (condition) {
        println("  ПРОШЕЛ  $label")
    } else {
        failures++
        println("  ПРОВАЛ  $label ${if (detail.isNotBlank()) "— $detail" else ""}")
    }
}

fun cacheDir(name: String) = java.io.File(System.getProperty("java.io.tmpdir"), "kache-consumer-$name")

fun recordCount(dir: File): Int = dir.listFiles().orEmpty().count { it.isFile && !it.name.endsWith(".tmp") }

fun main() {
    println("Kache 0.1.0 — проверка как внешней зависимости")
    println("  репозиторий: build/repo")
    println()

    persistenceAcrossProcesses()
    evictionStrategies()
    capacityLimit()
    readStrategiesAndCorruption()
    keySafety()

    println()
    if (failures == 0) println("ИТОГ: все проверки пройдены") else println("ИТОГ: провалено $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}

fun persistenceAcrossProcesses() = runBlocking {
    println("1. Персистентность: запись, затем чтение из \"нового\" движка")

    val dir = cacheDir("persist")
    dir.deleteRecursively()

    val first = L2KmpCache<String, UserProfile>(
        storageEngine = FileStorageEngine(dir.absolutePath),
        valueSerializer = serializer,
    )
    first.put("profile", UserProfile("u-1", "Vyacheslav"), ttl = 1.hours)
    check("запись создана на диске", recordCount(dir) == 1, "записей ${recordCount(dir)}")

    // Совершенно новый экземпляр движка поверх того же каталога — то, что происходит
    // после перезапуска процесса.
    val second = L2KmpCache<String, UserProfile>(
        storageEngine = FileStorageEngine(dir.absolutePath),
        valueSerializer = serializer,
    )
    val read = second.get("profile", CacheStrategy.CacheFirst, null).first()

    val success = read as? CacheResult.Success<*>
    check("прочитано с диска после \"перезапуска\"", success != null, "получено $read")
    check("origin = DISK", success?.origin == CacheOrigin.DISK, "origin=${success?.origin}")
    val profile = success?.data as? UserProfile
    check("значение целое", profile?.name == "Vyacheslav", "получено ${profile?.name}")
    println()
}

fun evictionStrategies() = runBlocking {
    println("2. Стратегии вытеснения")

    // Управляемые часы обязательны: на системных часах три записи и одно чтение укладываются
    // в одну миллисекунду, все метки совпадают, сортировка ничем не отличается и все четыре
    // стратегии выбрасывают один и тот же ключ. Так тест ничего не проверяет.
    data class Case(val strategy: EvictionStrategy, val label: String, val expected: String)

    val cases = listOf(
        Case(EvictionStrategy.LRU, "LRU", "b"),
        Case(EvictionStrategy.FIFO, "FIFO", "a"),
        Case(EvictionStrategy.MRU, "MRU", "a"),
        Case(EvictionStrategy.LIFO, "LIFO", "b"),
    )

    for ((strategy, label, expected) in cases.map { Triple(it.strategy, it.label, it.expected) }) {
        val dir = cacheDir("evict-${strategy.name.lowercase()}")
        dir.deleteRecursively()
        val engine = FileStorageEngine(dir.absolutePath)
        val clock = MutableTimeSource(1_000L)
        val cache = L2KmpCache<String, UserProfile>(
            storageEngine = engine,
            valueSerializer = serializer,
            timeSource = clock,
            maxSize = 2,
            evictionStrategy = strategy,
            touchGranularity = kotlin.time.Duration.ZERO,
        )

        cache.put("a", UserProfile("a", "A"), null)            // создан и прочитан в 1000
        clock.advance(1000.milliseconds)
        cache.put("b", UserProfile("b", "B"), null)            // создан в 2000
        clock.advance(1000.milliseconds)
        cache.get("a", CacheStrategy.CacheFirst, null).first() // "a" становится самым недавним, 3000
        clock.advance(1000.milliseconds)
        cache.put("c", UserProfile("c", "C"), null)            // 4000, вытеснение

        // Проверяем через движок, а не через имена файлов: имена кодируются, и привязка к
        // ним сломала бы harness при любой смене кодировки.
        val remaining = listOf("a", "b", "c").filter { engine.get(it) != null }
        val dropped = listOf("a", "b", "c").filterNot { it in remaining }
        println("  $label: остались $remaining, вытеснен $dropped (ожидался \"$expected\")")
        check("$label соблюдает лимит", remaining.size == 2, "осталось ${remaining.size}")
        check("$label вытеснил \"$expected\"", dropped == listOf(expected), "вытеснен $dropped")
        check("$label не вытеснил только что записанный ключ", "c" in remaining, "c вытеснен")
    }
    println()
}

fun capacityLimit() = runBlocking {
    println("3. Лимит размера")

    val dir = cacheDir("capacity")
    dir.deleteRecursively()
    val cache = L2KmpCache<String, UserProfile>(
        storageEngine = FileStorageEngine(dir.absolutePath),
        valueSerializer = serializer,
        maxSize = 5,
    )

    repeat(50) { i -> cache.put("key-$i", UserProfile("u$i", "User $i"), null) }
    check("лимит 5 соблюдён при 50 записях", recordCount(dir) == 5, "на диске ${recordCount(dir)}")

    val withoutLimit = L2KmpCache<String, UserProfile>(
        storageEngine = FileStorageEngine(dir.absolutePath),
        valueSerializer = serializer,
    )
    repeat(10) { i -> withoutLimit.put("free-$i", UserProfile("f$i", "Free $i"), null) }
    check("без лимита записи не вытесняются", recordCount(dir) == 15, "на диске ${recordCount(dir)}")
    println()
}

fun readStrategiesAndCorruption() = runBlocking {
    println("4. Стратегии чтения и повреждённые записи")

    val dir = cacheDir("strategies")
    dir.deleteRecursively()
    val cache = L2KmpCache<String, UserProfile>(
        storageEngine = FileStorageEngine(dir.absolutePath),
        valueSerializer = serializer,
    )

    var fetches = 0
    val fetcher: suspend (String) -> UserProfile = { fetches++; UserProfile("u-1", "Fetched") }

    val first = cache.get("k", CacheStrategy.CacheFirst, fetcher).first()
    check("CacheFirst идёт в сеть на пустом кэше", (first as? CacheResult.Success)?.origin == CacheOrigin.NETWORK, "$first")

    val second = cache.get("k", CacheStrategy.CacheFirst, null).first()
    check("CacheFirst берёт из кэша при повторе", (second as? CacheResult.Success)?.origin == CacheOrigin.DISK, "$second")
    check("fetcher вызван ровно один раз", fetches == 1, "вызовов $fetches")

    // Просроченная запись не отдаётся как свежая.
    val ttlDir = cacheDir("strategies-ttl")
    ttlDir.deleteRecursively()
    val shortTtl = L2KmpCache<String, UserProfile>(
        storageEngine = FileStorageEngine(ttlDir.absolutePath),
        valueSerializer = serializer,
    )
    shortTtl.put("exp", UserProfile("e", "Expired"), ttl = 1.milliseconds)
    Thread.sleep(20)
    val expired = shortTtl.get("exp", CacheStrategy.CacheFirst, null).first()
    check("просроченная запись не отдаётся", expired !is CacheResult.Success<*>, "$expired")

    // Порча заголовка: объявленная длина не помещается в файл.
    checkCorruptRecordIsAMiss("порченый заголовок", "v2|1|0|1|999|мусор")
    println()
}

/**
 * Пишет файл записи для ключа, затем перезаписывает его байтами [corruptBody].
 *
 * Имя файла не задаётся вручную: движок кодирует ключи, поэтому файл сначала создаётся
 * обычной записью, а потом портится найденный файл. Хардкод имени сломался бы при смене
 * кодировки и увёл бы проверку в сторону.
 */
suspend fun checkCorruptRecordIsAMiss(label: String, corruptBody: String) {
    val dir = cacheDir("corrupt")
    dir.deleteRecursively()
    val engine = FileStorageEngine(dir.absolutePath)
    val cache = L2KmpCache<String, UserProfile>(storageEngine = engine, valueSerializer = serializer)

    cache.put("victim", UserProfile("v", "Victim"), null)
    val file = dir.listFiles().orEmpty().single()
    file.writeText(corruptBody)

    val result = cache.get("victim", CacheStrategy.CacheFirst, null).first()
    check("$label даёт промах, а не исключение", result !is CacheResult.Success<*>, "$result")
}

fun keySafety() = runBlocking {
    println("5. Свойства ключей")

    val dir = cacheDir("keys")
    dir.deleteRecursively()
    val parent = dir.parentFile
    val engine = FileStorageEngine(dir.absolutePath)
    val cache = L2KmpCache<String, UserProfile>(storageEngine = engine, valueSerializer = serializer)

    // Раньше эти три ключа схлопывались в один файл: запись одного молча затирала другой,
    // а чтение любого возвращало значение чужого ключа.
    val colliding = listOf("user:1", "user/1", "user_1")
    colliding.forEachIndexed { index, key ->
        cache.put(key, UserProfile("u$index", "Value $index"), null)
    }
    check("разные ключи не делят один файл", recordCount(dir) == 3, "записей ${recordCount(dir)}")
    val values = colliding.mapIndexed { index, key ->
        (cache.get(key, CacheStrategy.CacheFirst, null).first() as? CacheResult.Success<*>)?.data as? UserProfile
    }
    check(
        "каждый ключ возвращает своё значение",
        values.map { it?.name } == listOf("Value 0", "Value 1", "Value 2"),
        "получено ${values.map { it?.name }}",
    )

    // Ключ ".." раньше указывал на родительский каталог: put падал, а remove мог удалить его.
    val traversalDir = cacheDir("keys-traversal")
    traversalDir.deleteRecursively()
    val traversalCache = L2KmpCache<String, UserProfile>(
        storageEngine = FileStorageEngine(traversalDir.absolutePath),
        valueSerializer = serializer,
    )
    traversalCache.put("..", UserProfile("t", "Traversal"), null)
    traversalCache.put(".", UserProfile("t", "Dot"), null)
    traversalCache.put("", UserProfile("t", "Empty"), null)
    check("ключи . и .. не выходят из каталога", recordCount(traversalDir) == 3, "записей ${recordCount(traversalDir)}")
    check("родительский каталог не тронут", parent?.isDirectory == true, "родителя нет")
    val traversalRead = traversalCache.get("..", CacheStrategy.CacheFirst, null).first()
    check("ключ .. читается как обычная запись", traversalRead is CacheResult.Success<*>, "$traversalRead")
    println()
}

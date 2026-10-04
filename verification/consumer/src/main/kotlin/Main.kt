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

fun main() {
    println("Kache 0.1.0 — проверка как внешней зависимости")
    println("  путь: /Users/vyacheslav/StudioProjects/Kache/build/repo")
    println()

    persistenceAcrossProcesses()
    evictionStrategies()
    capacityLimit()
    strategiesAndErrors()

    println()
    if (failures == 0) println("ИТОГ: все проверки пройдены") else println("ИТОГ: провалено $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}

fun persistenceAcrossProcesses() = runBlocking {
    println("1. Персистентность: запись, затем чтение из \"нового\" движка")

    val dir = cacheDir("persist")
    dir.deleteRecursively()

    val first = L2KmpCache<String, UserProfile>(storageEngine = FileStorageEngine(dir.absolutePath), valueSerializer = serializer)
    first.put("profile", UserProfile("u-1", "Vyacheslav"), ttl = 1.hours)
    check("запись создана на диске", File(dir, "profile").exists(), "файла нет")

    // Совершенно новый экземпляр движка поверх того же каталога — то, что происходит
    // после перезапуска процесса.
    val second = L2KmpCache<String, UserProfile>(storageEngine = FileStorageEngine(dir.absolutePath), valueSerializer = serializer)
    val read = second.get("profile", CacheStrategy.CacheFirst, null).first()

    check("прочитано с диска после \"перезапуска\"", read is CacheResult.Success<*>, "получено $read")
    val onDisk = read as? CacheResult.Success<*>
    check("origin = DISK", onDisk?.origin == CacheOrigin.DISK, "origin=${onDisk?.origin}")
    val profile = onDisk?.data as? UserProfile
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

        cache.put("a", UserProfile("a", "A"), null)          // создан и прочитан в 1000
        clock.advance(1000.milliseconds)
        cache.put("b", UserProfile("b", "B"), null)          // создан в 2000
        clock.advance(1000.milliseconds)
        cache.get("a", CacheStrategy.CacheFirst, null).first() // "a" становится самым недавним, 3000
        clock.advance(1000.milliseconds)
        cache.put("c", UserProfile("c", "C"), null)          // 4000, вытеснение

        val remaining = listOf("a", "b", "c").filter { File(dir, it).exists() }
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

    val onDisk = File(dir, "key-0").parentFile!!.listFiles()!!.count { it.isFile }
    check("лимит 5 соблюдён при 50 записи", onDisk == 5, "на диске $onDisk")

    val withoutLimit = L2KmpCache<String, UserProfile>(storageEngine = FileStorageEngine(dir.absolutePath), valueSerializer = serializer)
    repeat(10) { i -> withoutLimit.put("free-$i", UserProfile("f$i", "Free $i"), null) }
    val total = File(dir, "free-0").parentFile!!.listFiles()!!.count { it.isFile }
    check("без лимита записи не вытесняются", total == 15, "на диске $total, ожидалось 15")
    println()
}

fun strategiesAndErrors() = runBlocking {
    println("4. Стратегии чтения и ошибки")

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

    val stale = cache.get("k", CacheStrategy.CacheFirst, null).first()
    check("повторное чтение то же значение", ((stale as? CacheResult.Success)?.data as? UserProfile)?.name == "Fetched", "$stale")

    // Просроченная запись не отдаётся как свежая
    val shortTtl = L2KmpCache<String, UserProfile>(storageEngine = FileStorageEngine(dir.absolutePath), valueSerializer = serializer)
    shortTtl.put("exp", UserProfile("e", "Expired"), ttl = 1.milliseconds)
    Thread.sleep(20)
    val expired = shortTtl.get("exp", CacheStrategy.CacheFirst, null).first()
    check("просроченная запись не отдаётся", expired !is CacheResultValue, "$expired")

    // Ошибка десериализации
    val broken = java.io.File(dir, "broken")
    broken.writeText("v2|1|0|1|999|мусор")
    val afterBroken = L2KmpCache<String, UserProfile>(storageEngine = FileStorageEngine(dir.absolutePath), valueSerializer = serializer)
    val result = afterBroken.get("broken", CacheStrategy.CacheFirst, null).first()
    check("битый файл даёт промах, а не исключение", result !is CacheResultValue, "$result")
    println()
}

typealias CacheResultValue = io.github.slavikjunior.kache.core.CacheResult.Success<*>
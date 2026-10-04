# Kache

<div align="center">

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF.svg?logo=kotlin)](http://kotlinlang.org)
[![AGP](https://img.shields.io/badge/AGP-9.4.1-3DDC84.svg?logo=androidstudio)](https://developer.android.com/studio)
[![Coroutines](https://img.shields.io/badge/coroutines-1.11.0-795548.svg)](https://github.com/Kotlin/kotlinx.coroutines)
[![Room](https://img.shields.io/badge/Room-2.8.5-E8F0FE.svg?logo=android)](https://developer.android.com/kotlin/room)
[![SQLite](https://img.shields.io/badge/androidx.sqlite-2.7.1-E8F0FE.svg)](https://developer.android.com/kotlin/room)
[![KSP](https://img.shields.io/badge/KSP-2.3.12-2E6D82.svg)](https://kotlinlang.org/docs/ksp-overview.html)
[![Lifecycle](https://img.shields.io/badge/Lifecycle-2.11.0-3DDC84.svg?logo=android)](https://developer.android.com/jetpack/androidx/releases/lifecycle)
[![minSdk](https://img.shields.io/badge/minSdk-23-8A8A8A.svg)](https://developer.android.com)
[![Tests](https://img.shields.io/badge/tests-468%20green-1D6B4F.svg)](#покрытие-тестами)

[English](README.md) · **Русский**

</div>
[![Maven Central](https://img.shields.io/badge/maven--central-0.1.0-blue)](https://central.sonatype.com/artifact/io.github.slavikjunior.kache/cache-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Кэш для Kotlin Multiplatform с двумя уровнями, реактивным пайплайном стратегий и одним API,
который ведёт себя одинаково на Android и iOS.

```kotlin
cache.get(
    key = "user-42",
    strategy = CacheStrategy.CacheFirst,
    fetcher = { api.loadUser("42") },
).collect { result ->
    when (result) {
        is CacheResult.Success -> render(result.data, origin = result.origin)
        is CacheResult.Loading -> showSpinner()
        is CacheResult.Error   -> showError(result.error)
    }
}
```

В этом фрагменте вся идея: вы описываете, **что** хотите, а кэш решает, откуда взять —
из памяти, с диска или из сети, и сообщает, откуда он это взял.

---

## Содержание

- [Зачем ещё одна библиотека кэша](#зачем-ещё-одна-библиотека-кэша)
- [Поддержка платформ](#поддержка-платформ)
- [Установка](#установка)
- [Ключевые понятия](#ключевые-понятия)
  - [Уровни и путь чтения](#уровни-и-путь-чтения)
  - [Стратегии](#стратегии)
  - [CacheOrigin: откуда взялись данные](#cacheorigin-откуда-взялись-данные)
  - [Исключения](#исключения)
- [Рецепты](#рецепты)
  - [1. Кэш в памяти (все платформы)](#1-кэш-в-памяти-все-платформы)
  - [2. Файловый кэш (JVM / Android)](#2-файловый-кэш-jvm--android)
  - [3. Room-кэш (JVM / Android / iOS)](#3-room-кэш-jvm--android--ios)
  - [4. Двухуровневый кэш: память перед диском](#4-двухуровневый-кэш-память-перед-диском)
  - [5. Типизированные ключи вместо строк](#5-типизированные-ключи-вместо-строк)
  - [6. Кеширующая ViewModel](#6-кеширующая-viewmodel)
  - [7. Очистка кэша вместе с экраном](#7-очистка-кэша-вместе-с-экраном)
  - [8. Реакция на нехватку памяти](#8-реакция-на-нехватку-памяти)
  - [9. Политика повторов](#9-политика-повторов)
  - [10. Ограничение размера L2](#10-ограничение-размера-l2)
  - [11. Тестирование TTL без ожидания](#11-тестирование-ttl-без-ожидания)
- [Какой бэкенд выбрать?](#какой-бэкенд-выбрать)
- [Какие зависимости приносит библиотека](#какие-зависимости-приносит-библиотека)
- [Архитектура](#архитектура)
- [Сборка из исходников](#сборка-из-исходников)
- [Статус проекта](#статус-проекта)
- [Лицензия](#лицензия)

---

## Зачем ещё одна библиотека кэша

Хороших универсальных кэшей достаточно. Ниша, которую закрывает эта библиотека, уже:

- **Один API для обоих уровней.** Большинство библиотек заставляют выбирать между кэшем в
  памяти и кэшем на диске на уровне типов. Здесь `L1MemoryCache` и `StorageEngine`
  подключаются к одному контракту `KmpCache`, поэтому перенос значения из памяти на диск —
  не ваша задача.
- **Вам говорят, откуда данные.** `CacheOrigin` различает `MEMORY`, `DISK`, `NETWORK`,
  `MEMORY_STALE` и `DISK_STALE`. Это разница между «экран что-то показал» и «экран
  показал, и я знаю, что это устаревшая ревалидация, которая ещё идёт».
- **Обновление не очищает экран.** `CachedState` сохраняет прежнее значение, пока
  `isLoading` равен `true`. Если разложить сырые события кэша по `StateFlow`, значение
  потерялось бы.
- **Платформы — первоклассные.** Один и тот же код кэша работает на Android и iOS, включая
  постоянное хранилище, через подменяемый `StorageEngine`.

## Поддержка платформ

| Модуль | JVM | Android | iOS | Что это |
|---|:---:|:---:|:---:|---|
| `:cache-core` | ✅ | ✅ | ✅ | Кэш в памяти, стратегии, SPI `StorageEngine` |
| `:cache-storage` | ✅ | ✅ | ❌ | Файловый L2-бэкенд |
| `:cache-store-room` | ✅ | ✅ | ✅¹ | Room/SQLite L2-бэкенд |
| `:cache-android` | ❌ | ✅ | ❌ | Удобства для Android (`l2Cache`, кеширующая ViewModel) |
| `:sample-android` | ❌ | ✅ | ❌ | Приложение-пример |

> ¹ `:cache-store-room` поддерживает `iosArm64` и `iosSimulatorArm64`. Варианта `iosX64` нет,
> потому что `androidx.sqlite` 2.7.1 его не публикует и зависимость не разрешается для
> этого таргета. У `:cache-core` вариант `iosX64` есть.

Версия `0.1.0` проверена только на локальном Maven-репозитории. Координаты пока не
разрешаются из `mavenCentral()` — см. [Статус проекта](#статус-проекта).

## Установка

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

Дальше подключите нужные модули. Обязателен только `cache-core`.

```kotlin
// build.gradle.kts модуля Kotlin Multiplatform
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-core:0.1.0")

            // L2-бэкенды по выбору. Оба реализуют StorageEngine — берите подходящий.
            implementation("io.github.slavikjunior.kache:cache-storage:0.1.0")    // JVM/Android
            implementation("io.github.slavikjunior.kache:cache-store-room:0.1.0") // JVM/Android/iOS
        }
    }
}
```

Из **обычного Android-модуля** (вообще без блока `kotlin { }` — KMP-плагин на вашей стороне
не нужен):

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.slavikjunior.kache:cache-core:0.1.0")
    implementation("io.github.slavikjunior.kache:cache-android:0.1.0")
}
```

Gradle Module Metadata несёт вариант под каждую платформу, поэтому обычный Android-проект
подхватывает AAR и никогда не видит артефакты Kotlin/Native. В classpath попадает только
`kotlin-stdlib`, `kotlinx-coroutines` и `kotlinx-datetime`.

Требуется `minSdk 23`.

## Ключевые понятия

### Уровни и путь чтения

```
                 ┌──────────────────────────────┐
   get(key) ───▶ │         CachePipeline        │
                 └──────────────┬───────────────┘
                                │
                    ┌───────────▼───────────┐
                    │   CacheStrategy       │
                    │  задаёт порядок       │
                    └───────────┬───────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
        ┌──────────┐     ┌──────────┐      ┌────────────┐
        │   L1     │     │ fetcher  │      │    L2      │
        │  память  │     │  сеть    │      │  Storage   │
        │   (LRU)  │     │          │      │  Engine    │
        └──────────┘     └──────────┘      └────────────┘
```

- **L1** — это `L1MemoryCache`: LRU-карта с опциональным TTL на запись, под защитой
  `Mutex`, поэтому она безопасна при конкурентном доступе.
- **L2** — всё, что реализует `StorageEngine`. Туда попадают только при промахе в L1, и
  прочитанное значение автоматически поднимается в память.
- **fetcher** — ваша лямбда. Кэш вызывает её только тогда, когда стратегии это нужно, так
  что Satisfied-кэш никогда не ходит в сеть.

Попадание в L2 даёт `CacheOrigin.DISK`; тот же самый запрос в следующий раз даёт
`CacheOrigin.MEMORY`, потому что значение было поднято.

### Стратегии

| Стратегия | Поведение | Сколько состояний |
|---|---|---|
| `CacheFirst` | Отдать свежее из кэша; в сеть только при промахе. Просроченное считается промахом. | одно |
| `NetworkFirst` | Сначала сеть; при неудаче — кэш. | одно |
| `CacheAndNetwork` | Показать кэш сразу, затем обновить. | до двух |
| `StaleWhileRevalidate` | Показать кэш **даже просроченный**, затем обновить. | до двух |

Две детали, о которых стоит знать:

- В `CacheFirst` просроченная запись считается промахом, поэтому клиент никогда не
  получит данные, которые тут же выбросит.
- В `NetworkFirst` неудачный запрос **не** сообщается, если кэш может его заменить, —
  вместо этого значение отдаётся с честным origin.

### CacheOrigin: откуда взялись данные

```kotlin
enum class CacheOrigin {
    MEMORY, MEMORY_STALE,   // прочитано из уровня памяти
    DISK, DISK_STALE,       // прочитано из постоянного хранилища
    NETWORK,                // получено из fetcher
}
```

Значения с `_STALE` встречаются только при `StaleWhileRevalidate`: они говорят, что на
экране то, что уже пережило свой TTL, и обновление идёт следом.

### Исключения

Всё, что бросает библиотека, — это `KacheException`, поэтому одного `catch` достаточно:

```kotlin
sealed class KacheException : Exception {
    class NetworkException(cause)          // fetcher не отработал
    class CacheMissException(message)      // ничего нет и fetcher не передан
    class ExpiredException(key)            // запись просрочена
    class DiskReadException(cause)
    class DiskWriteException(cause)
    class SerializationException(cause)
    class UnknownKacheException(cause)
}
```

Отмена никогда не оборачивается: отменённая корутина пробрасывается как
`CancellationException`, поэтому отмена сбора не путается с неудачным запросом.

---

## Рецепты

### 1. Кэш в памяти (все платформы)

```kotlin
import io.github.slavikjunior.kache.core.CacheStrategy
import io.github.slavikjunior.kache.core.L1MemoryCache
import kotlin.time.Duration.Companion.seconds

val cache = L1MemoryCache<String, String>(
    maxSize = 100,           // сколько записей до начала вытеснения
    defaultTtl = 60.seconds, // null — записи не просрочатся
)

cache.put("greeting", "hello")
cache.get("greeting")       // StorageRecord<String>?
cache.size()                // сколько записей
cache.removeExpired()       // вычистить то, у чего истёк TTL
```

`get` возвращает `null` для отсутствующего **или** просроченного ключа и попутно удаляет
его. Используйте `getStale`, когда просроченное значение нужно намеренно.

### 2. Файловый кэш (JVM / Android)

Один файл на ключ, запись атомарная через временный файл, поэтому падение посреди записи не
оставит половину данных.

```kotlin
import io.github.slavikjunior.kache.core.L2KmpCache
import io.github.slavikjunior.kache.storage.FileStorageEngine
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer
import kotlin.time.Duration.Companion.hours

// Перегрузка с движком оставляет владение вами — она нужна, если вам ещё требуется
// size(), предварительное заполнение или закрытие.
val engine = FileStorageEngine("/data/local/tmp/my-cache")

val cache = L2KmpCache<String, UserProfile>(
    storageEngine = engine,
    valueSerializer = KotlinxJsonSerializer(UserProfile.serializer()),
    defaultTtl = 1.hours,
)
```

На Android есть ярлык с `Context` — см. [рецепт 6](#6-кеширующая-viewmodel) и фабрику
`l2Cache` ниже.

### 3. Room-кэш (JVM / Android / iOS)

Пригодится, когда нужен индексируемый архив, миграции, либо когда кэш должен жить на
платформе, где файловый API неудобен. На iOS это **единственный** L2-бэкенд.

```kotlin
// JVM: путь к файлу, родительский каталог должен существовать
val cache = createFromFile("/tmp/kache.db")

// Android: приватная директория приложения
val cache = createFromContext(applicationContext, "kache.db")

// iOS: директория кэшей приложения
val cache = createFromCachesDirectory("kache.db")
```

Все фабрики возвращают `RoomStorageEngine`, который является `StorageEngine` — его можно
передать в любой класс кэша точно так же, как файловый движок.

### 4. Двухуровневый кэш: память перед диском

`ChainKmpCache` ставит `L1MemoryCache` перед любым `StorageEngine`. Это конфигурация,
которая нужна большинству приложений.

```kotlin
import io.github.slavikjunior.kache.core.ChainKmpCache
import io.github.slavikjunior.kache.core.L1MemoryCache
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

val cache = ChainKmpCache<String, UserProfile>(
    l1Cache = L1MemoryCache(maxSize = 200, defaultTtl = 5.minutes),
    l2Storage = FileStorageEngine("/tmp/my-cache"),
    serializer = KotlinxJsonSerializer(UserProfile.serializer()),
    defaultTtl = 1.days,
)
```

Оба уровня получают одни и те же часы, поэтому не могут разойтись во мнении о просрочке:
значение, которое L1 считает свежим, не будет отдано как свежее, если L2 считает иначе.

### 5. Типизированные ключи вместо строк

По умолчанию ключи типизированы и приводятся через `toString()`. Для идентификаторов это
нормально, а для data-классов — нет. Задайте своё отображение:

```kotlin
val cache = L2KmpCache<UserId, Profile>(
    storageEngine = engine,
    valueSerializer = KotlinxJsonSerializer(Profile.serializer()),
    keyToString = { it.raw },          // вместо UserId(dataClassToString=…)
    defaultTtl = 1.hours,
)
```

Отображение обязано давать разные строки для разных ключей: разные ключи могут
схлопнуться в один файл, и это будет тихая потеря данных, а не ошибка.

### 6. Кеширующая ViewModel

`KacheableViewModel` убирает повторяющуюся в каждом экране обвязку: `StateFlow`, корутину
на каждое чтение и отображение событий кэша в состояние экрана.

```kotlin
import io.github.slavikjunior.kache.android.KacheableViewModel
import io.github.slavikjunior.kache.android.l2Cache
import io.github.slavikjunior.kache.storage.KotlinxJsonSerializer

class ProfileViewModel(application: Application) :
    KacheableViewModel<String, Profile>(
        application = application,
        cache = l2Cache(application, KotlinxJsonSerializer(Profile.serializer())),
    ) {

    init {
        load(key = "profile-42", fetcher = { repository.load("profile-42") })
    }
}
```

Экран тогда не хранит состояния вовсе:

```kotlin
val state by viewModel.kacheState.collectAsStateWithLifecycle()

Text(state.data?.name ?: "—")
Text("origin=${state.origin}")
if (state.isLoading) CircularProgressIndicator()
state.error?.let { Button(onClick = viewModel::retry) { Text("Повторить") } }
```

`CachedState` сохраняет `data` заполненным, пока `isLoading` равен `true`, поэтому
обновление приглушает экран, а не очищает его. Ошибка может нести пригодное запасное
значение — поэтому ошибка не означает пустое состояние.

Тип состояния намеренно свободен от фреймворков и живёт в `:cache-core`, поэтому тот же
холдер работает и вне ViewModel:

```kotlin
// Кроссплатформенно: никаких Android-типов
val holder = KacheStateHolder(scope = myScope, cache = myCache)
holder.load(key = "profile-42", fetcher = { repository.load("profile-42") })
```

`load` помечен `open`, поэтому подкласс может его переопределить, чтобы добавить логирование
или аналитику, и не обязан писать переадресацию вызова, который не меняет.

**Не кладите сам кэш во ViewModel.** ViewModel уничтожается при каждом повороте экрана,
поэтому лежащий в нём кэш теряет уровень памяти на каждой ротации. В приложении-примере
кэш живёт в отдельном объекте на процесс — именно по этой причине.

### 7. Очистка кэша вместе с экраном

```kotlin
import io.github.slavikjunior.kache.android.clearWhenScopeCancelled

// Очищает, когда ViewModel уничтожена. Возвращённый Job завершается после самой
// очистки, поэтому его можно await в тестах.
cache.clearWhenScopeCancelled(viewModelScope)
```

Расширение срабатывает и при отмене scope, и при его нормальном завершении. Очистка
намеренно **не** выполняется внутри этого scope: к моменту запуска он уже завершён, поэтому
работа оттуда либо отменилась бы сразу, либо вызвала бы взаимоблокировку у ожидающего Job.

Если кэш должен пережить все экраны — передайте scope уровня приложения.

### 8. Реакция на нехватку памяти

```kotlin
import io.github.slavikjunior.kache.android.registerCacheMemoryPressureCallbacks
import io.github.slavikjunior.kache.android.unregisterCacheMemoryPressureCallbacks

val callbacks = application.registerCacheMemoryPressureCallbacks(appScope, cache)
// ...
application.unregisterCacheMemoryPressureCallbacks(callbacks)
```

Очищают только `TRIM_MEMORY_COMPLETE` и `onLowMemory`. Промежуточные уровни вроде
`TRIM_MEMORY_UI_HIDDEN` намеренно сохраняют данные: процесс жив и полностью ожидаем назад,
и очистка превратила бы переживаемую системой обрезку в видимую перезагрузку.

Снимайте регистрацию, когда кэши перестают нужны, иначе приложение удерживает их до конца
жизни процесса.

### 9. Политика повторов

По умолчанию `RetryPolicy.None`: кэш не должен усиливать трафик во время аварии.

```kotlin
import io.github.slavikjunior.kache.core.RetryPolicy
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

l2Cache(
    context = applicationContext,
    serializer = KotlinxJsonSerializer(Profile.serializer()),
    // 3 попытки, экспоненциальная задержка, 20% джиттера, чтобы клиенты не били одновременно
    retryPolicy = RetryPolicy.Exponential(
        maxAttempts = 3,
        initialDelay = 200.milliseconds,
        maxDelay = 2.seconds,
        multiplier = 2.0,
        jitterRatio = 0.2,
    ),
)
```

Готовые варианты: `RetryPolicy.aggressive()` — 5 попыток от 250 мс;
`RetryPolicy.fixed(n, ms)` — равные интервалы; `RetryPolicy.Exponential(...)` — полная
раскладку. Джиттер масштабирует задержку в диапазон `[1 - jitterRatio, 1]`.

> `-ktx`-артефакты Lifecycle стали пустыми заглушками в версии 2.9. `viewModelScope` и
> `AndroidViewModel` теперь живут в `androidx.lifecycle:lifecycle-viewmodel` — именно от
> него зависит `:cache-android`.

### 10. Ограничение размера L2

```kotlin
val cache = L2KmpCache(
    storageEngine = FileStorageEngine(cacheDir),
    valueSerializer = KotlinxJsonSerializer(),
    maxSize = 500,                            // null = без лимита; 0 и меньше тоже без лимита
    evictionStrategy = EvictionStrategy.LRU,
    autoReapEvery = 10.minutes,               // null = не удалять просроченное по расписанию
)
```

Три независимые политики, все выключены по умолчанию:

| Параметр | По умолчанию | Что делает |
|---|---|---|
| `maxSize` | `null` | Держит не больше указанного числа записей, выбрасывая худшего кандидата при каждой записи сверх лимита. Считается в записях, а не в байтах. |
| `evictionStrategy` | `EvictionStrategy.LRU` | Кого выбрасывает `maxSize`: `LRU`, `FIFO`, `MRU` или `LIFO`. |
| `autoReapEvery` | `null` | Удаляет просроченные записи по интервалу. Без него просроченная запись лежит, пока её ключ не прочитают или не перезапишут. |

**Просроченные записи всегда идут первыми**, что бы ни говорила стратегия. Просроченную запись
не отдаст ни один путь чтения, поэтому выбросить её ничего не стоит; свежую — стоит.

**Отслеживание обращений следует за лимитом, а не за отдельным переключателем.** `LRU` и `MRU`
 ранжируют по `lastAccessedAt`, которое чтение обновляет не чаще раза в `touchGranularity`
(по умолчанию минута). Именно этот пол не даёт ключу, который читают в цикле, стоить записи на
каждое чтение. Но без `maxSize` ничего никогда не вытеснится, а значит, эти отметки никто и не
прочитает — кэш, который никто не просил ограничить, **не делает вообще ни одной лишней записи**.

```kotlin
// Точная свежесть обращений ценой записи на каждое чтение. Редко то, что нужно.
L2KmpCache(engine, serializer, maxSize = 100, touchGranularity = Duration.ZERO)
```

Оба кэша принимают одинаковые параметры, поэтому `ChainKmpCache` ограничивает L2, а L1 остаётся
ограниченным через `L1MemoryCache(maxSize = …)`. `autoReapEvery` у `ChainKmpCache` чистит ещё и L1.

`autoReapEvery` поднимает корутину, живущую столько же, сколько кэш, поэтому кэш с такой
настройкой предполагается долгоживущим — уровня приложения. Вызывайте `stopAutoReap()`, когда
кэш переживает своего владельца или когда уборку надо остановить, оставив кэш рабочим.

### 11. Тестирование TTL без ожидания

`TimeSource` внедряется, поэтому просрочка детерминирована и ни один тест не спит.

```kotlin
import io.github.slavikjunior.kache.core.MutableTimeSource
import kotlin.time.Duration.Companion.milliseconds

val clock = MutableTimeSource(initialTimeMillis = 0L)
val cache = L1MemoryCache<String, String>(maxSize = 10, timeSource = clock)

cache.put("k", "v", ttl = 100.milliseconds)
clock.advance(101.milliseconds)

assertNull(cache.get("k"))
```

---

## Какой бэкенд выбрать?

| Задача | Что брать |
|---|---|
| Только память или общий UI-кэш | `L1MemoryCache` |
| Быстрый локальный диск, один файл на ключ, без схемы | `:cache-storage` (`FileStorageEngine`) |
| Индексируемое хранилище, миграции или единственный вариант на iOS | `:cache-store-room` (Room) |
| Память перед диском | `ChainKmpCache` + любой `StorageEngine` |

Чтобы добавить свой, реализуйте `StorageEngine`. Это пять suspend-функций, и библиотека не
предполагает ничего сверх них.

## Какие зависимости приносит библиотека

| Модуль | Транзитивные зависимости |
|---|---|
| `:cache-core` | `kotlinx-coroutines-core`, `kotlinx-datetime` |
| `:cache-storage` | предыдущие + `kotlinx-serialization-json` |
| `:cache-store-room` | предыдущие + `androidx.room`, `androidx.sqlite` |
| `:cache-android` | предыдущие + `androidx.lifecycle:lifecycle-viewmodel` |

Ни UI-фреймворков, ни DI, ни фасада логирования. `:cache-android` намеренно не тянет Room и
WorkManager.

## Архитектура

```
        ┌──────────────────────────────┐
        │  :cache-core   (commonMain)  │
        │                              │
        │  KmpCache   ← контракт       │
        │  CacheStrategy               │
        │  CacheResult / CacheOrigin   │
        │  RetryPolicy                 │
        │  L1MemoryCache               │
        │  StorageEngine  ← SPI        │
        │  KacheStateHolder            │
        └───────┬──────────────┬───────┘
                │              │
   ┌────────────▼──────┐  ┌────▼──────────────┐
   │  :cache-storage   │  │ :cache-store-room │
   │  FileStorageEngine│  │ RoomStorageEngine │
   │  jvmCommonMain    │  │ jvm/android/ios   │
   └───────────────────┘  └───────────────────┘

        ┌──────────────────────────────┐
        │  :cache-android  (android)   │
        │  l2Cache, KacheableViewModel │
        │  clearWhenScopeCancelled     │
        │  memory-pressure callbacks   │
        └──────────────────────────────┘
```

`:cache-core` владеет политикой и зависит только от корутин. Бэкенды зависят от него и
никогда друг от друга. `:cache-android` — слой удобства, хранилища не добавляет.

`FileStorageEngine` живёт в source set `jvmCommonMain`, потому что JVM и Android используют
одну и ту же реализацию на `java.io`; раньше он дублировался по платформам.

## Сборка из исходников

```bash
./gradlew check                    # тесты: JVM, Android-хост, iOS-симулятор
./gradlew apiCheck                 # проверка совместимости ABI
./gradlew :sample-android:assembleDebug
./gradlew publishAllPublicationsToLocalRepository
```

Компиляцию и линковку iOS нужно запрашивать явно, потому что `./gradlew check` этого не
доказывает: `iosX64Test` выключен на Apple Silicon, а `iosArm64Test` требует физического
устройства.

```bash
./gradlew :cache-core:compileKotlinIosArm64 \
          :cache-core:compileKotlinIosSimulatorArm64 \
          :cache-core:compileKotlinIosX64 \
          :cache-core:iosArm64MainBinaries \
          :cache-core:iosSimulatorArm64MainBinaries \
          :cache-core:iosX64MainBinaries
```

## Статус проекта

`0.1.0`. Все семь фаз плана реализованы, плюс лимиты размера, стратегии вытеснения и
опциональная уборка просроченных записей.

### Покрытие тестами

468 тестов, зелёные на каждом хосте:

| Модуль | JVM | Android-хост | iOS-симулятор |
|---|---:|---:|---:|
| `:cache-core` | 113 | 113 | 113 |
| `:cache-storage` | 47 | — | — |
| `:cache-store-room` | 23 | 7 | 20 |
| `:cache-android` | — | 32 | — |

`:cache-core` гоняет один и тот же набор `commonTest` на трёх хостах, поэтому число там совпадает
по построению. У `:cache-store-room` один общий контракт и тонкие подклассы на платформу,
которые поставляют только фабрику.

**Известные пробелы, названные прямо:**

- **Android API не покрыт проверкой совместимости ABI.** BCV цепляется за Android-таргет
  KMP только когда его compilation назван `release`, а плагин AGP KMP даёт `main`, поэтому
  `:cache-android` публикует AAR без golden-файла. Пробел закрыт тестами, а не устранён.
- `RoomStorageEngineFactory.createFromContext` не проверяется в рантайме host-теста:
  JNI-библиотека bundled-драйвера собрана под Android ABI и не грузится в host-JVM. Покрыта
  компиляцией, опубликованным AAR и приложением-примером.
- **Схема версии 2 — ломающее изменение для существующей базы Room.** `:cache-store-room`
  перешёл с версии 1 на 2 ради колонки `last_accessed_at`. Миграция приложена и подключена во
  всех фабриках, так что существующие строки переносятся и бэкфиллятся из `created_at` — до
  первого прочтения они ранжируются по времени записи. Именно бэкфилл, а не значение по
  умолчанию колонки, не даёт обновлённому кэшу считать каждую старую строку наименее
  недавно использованной и выбросить их все при первом же вытеснении.
- **Существующий файловый кэш переживает смену заголовка.** В запись на диске добавлено поле,
  поэтому декодер читает два формата. Различает он их по маркеру версии в начале, а не по числу
  разделителей: посчитать их нельзя, потому что payload — произвольные байты и может содержать
  сам разделитель. Запись старого формата читается с временем обращения, равным времени записи —
  ровно тот же бэкфилл, что делает миграция базы, — так что записи обоих форматов ранжируются
  одинаково, пока их не прочитают. Файл *будущего* формата или вообще чужого формата удаляется,
  а не угадывается: каталог кэша расходный.
- Публикации в Maven Central пока нет. Оставшиеся ручные шаги (верификация пространства имён
  и Portal User Token) описаны в заметках о релизе.

## Лицензия

MIT. См. [LICENSE](LICENSE).
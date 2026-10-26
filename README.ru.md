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
[![Tests](https://img.shields.io/badge/tests-541%20green-1D6B4F.svg)](#покрытие-тестами)

[English](README.md) · **Русский**

</div>
[![Maven Central](https://img.shields.io/badge/maven--central-not%20published%20yet-lightgrey)](#статус-проекта)
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
  - [Требования к потребителю](#требования-к-потребителю)
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
- [Вопросы безопасности](#вопросы-безопасности)
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
подхватывает AAR и никогда не видит артефакты Kotlin/Native. Для `:cache-core` в classpath
попадают только `kotlin-stdlib` и `kotlinx-coroutines`; L2-бэкенд добавляет
`kotlinx-serialization` (через `:cache-storage`) либо Room/SQLite (через `:cache-store-room`).

### Требования к потребителю

| Требование | Значение | Почему |
|---|---|---|
| Kotlin | **2.4.20 или новее** | Публикуемые метаданные собраны компилятором 2.4.20, а API использует `kotlin.time.Duration` / `kotlin.time.Clock`. Более старый компилятор метаданные не прочитает. |
| Android Gradle Plugin | **9.x** | Android-артефакты производит `com.android.kotlin.multiplatform.library`; AGP 9 вдобавок включает поддержку Kotlin. |
| `compileSdk` | **36 или новее** | Каждый библиотечный модуль записывает `compileSdk 36` в метаданные AAR, а AGP отказывается подключать AAR, собранный против более нового API, чем у потребителя. |
| `minSdk` | **23** | Нижняя граница, объявленная всеми четырьмя артефактами. |
| Gradle | **9.x** | Сборка идёт на 9.6.1 и публикует стандартные Gradle Module Metadata. |
| iOS-таргеты | `:cache-core` — `iosArm64`, `iosSimulatorArm64`, `iosX64`; `:cache-store-room` — только `iosArm64`, `iosSimulatorArm64` | У `androidx.sqlite` 2.7.1 нет варианта `iosX64`, поэтому проект с таргетом `iosX64` обязан держать `:cache-store-room` вне `commonMain`. |

`:cache-storage` существует только для jvm/android, поэтому его место — `jvmMain`/`androidMain`,
но не `commonMain` проекта, который целится ещё и в iOS.

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

### Как написать свой бэкенд

У `StorageEngine` **пять обязательных** и **три необязательных** члена. У необязательных есть
дефолты, которые не ломают бэкенд, но каждый из них молча отключает фичу — прочитайте
таблицу, прежде чем решить, что какой-то можно пропустить.

| Член | Обязателен | Дефолт, если не реализовать | Что вы теряете |
|---|---|---|---|
| `get(key)` | да | — | — |
| `put(key, record)` | да | — | — |
| `remove(key)` | да | — | — |
| `clear()` | да | — | — |
| `size()` | да | — | — |
| `removeExpired(now)` | нет | возвращает `0` | Просроченные записи **не удаляются никогда**, кэш растёт без предела. Никто не вызывает это по расписанию за вас. |
| `touch(key, accessedAt)` | нет | возвращает `false` | `lastAccessedAt` не меняется, поэтому `EvictionStrategy.LRU` и `MRU` молча ведут себя как `FIFO`. |
| `evictionCandidates(strategy, limit, now)` | нет | возвращает пустой список | `maxSize` **молча не делает ничего**: кэш остаётся сверх лимита, и никакого предупреждения не будет. |

Последняя строка самая опасная. Бэкенд только с пятью обязательными функциями выглядит
полностью здоровым, пока `maxSize` не работает вообще. Если задаёте `maxSize` — реализуйте
`evictionCandidates`; если используете `LRU` или `MRU` — реализуйте ещё и `touch`.

`evictionCandidates` должен возвращать ключи, пригодные для передачи в `remove`, худший
кандидат первым, и просроченные записи раньше того, что выберет стратегия, — порядок, на
который опирается библиотека, описан в [рецепте 10](#10-ограничение-размера-l2).

## Вопросы безопасности

Кэш хранит то, что приложение сочло нужным сохранить, а это часто данные, которые стоит
защищать. Что библиотека с этим делает и чего не делает.

**Данные в кэше не шифруются.** Payload записывается ровно в том виде, в каком его отдал
сериализатор. Если приложение кэширует токены или персональные данные, они читаемы всем, кто
может прочитать файл, включая резервную копию устройства. Шифрования в библиотеке нет:
оберните сериализатор, если оно нужно, или не кэшируйте секреты.

**Куда попадают данные — решает приложение, и дефолты у бэкендов разные.** Room и
`FileStorageEngine` через `createFromContext` используют **cache**-каталог приложения, который
Android исключает из Auto Backup и может очистить в любой момент. iOS-фабрика
`createFromCachesDirectory` использует `NSCachesDirectory` по той же причине. JVM-фабрики
принимают путь от вас, поэтому бэкапимое или общее расположение возможно — но это уже ваше
решение, а не дефолт библиотеки.

**Ключи попадают на диск.** Файловый бэкенд кодирует ключ в имя файла, и кодирование
обратимо, поэтому ключ восстанавливается из листинга каталога. Ключ — это идентификатор, а не
секрет: не кладите в него токен, e-mail или идентификатор сессии. Разные ключи всегда
отображаются в разные файлы, поэтому два ключа не могут схлопнуться в одну запись и начать
отдавать значение друг друга.

**Задавайте `maxSize` для всего, что живёт дольше экрана.** Кэш без лимита растёт, пока на
устройстве не кончится место, а просроченные записи удаляются только при чтении или
перезаписи ключа. Обе фабрики по умолчанию оставляют кэш неограниченным намеренно —
ограничение включает ещё и трекинг обращений, который стоит одной записи на ключ за окно, —
поэтому лимит передаёте вы.

**У записей нет аутентификации.** Ничто не заметит значение, изменённое на диске, а срок
жизни записи определяют её же таймстемпы, поэтому тот, кто может писать в каталог кэша,
способен удержать подменённое значение живым. Считайте каталог кэша недоверенным вводом:
библиотека считает его таким и превращает нечитаемую запись в промах, а не в ошибку.

**Недоверенный ввод ограничен.** Запись больше `maxRecordBytes` (по умолчанию 16 МиБ)
отвергается при записи и не загружается при чтении, а запись, которую не удалось
десериализовать, отбрасывается вместо того, чтобы быть отданной или брошенной исключением.

**Ни сети, ни логов, ни телеметрии.** Библиотека не делает I/O помимо того бэкенда, который
вы ей дали, ничего не пишет в лог и никуда ничего не отправляет.

Сообщить об уязвимости — см. [SECURITY.md](SECURITY.md).

## Какие зависимости приносит библиотека

| Модуль | Транзитивные зависимости |
|---|---|
| `:cache-core` | `kotlinx-coroutines-core` |
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

`StorageEngine` — точка расширения. Пять членов обязательны; у `removeExpired`, `touch`
и `evictionCandidates` есть дефолты, которые не ломают бэкенд, но молча отключают уборку,
ранжирование по LRU и `maxSize` соответственно — прочитайте
[«Как написать свой бэкенд»](#как-написать-свой-бэкенд), прежде чем опускать хоть один.

`StorageRecord` несёт `lastAccessedAt` рядом с `createdAt` и `ttl`. Время обращения нужно для
[EvictionStrategy](#10-ограничение-размера-l2): по нему ранжируют `LRU` и `MRU`, и оно
обновляется только у кэша, у которого вообще задан `maxSize`.

`:cache-core` владеет политикой и зависит только от корутин. Бэкенды зависят от него и
никогда друг от друга. `:cache-android` — слой удобства, хранилища не добавляет.

`FileStorageEngine` живёт в source set `jvmCommonMain`, потому что JVM и Android используют
одну и ту же реализацию на `java.io`; раньше он дублировался по платформам.

## Сборка из исходников

```bash
./gradlew check                    # тесты: JVM, Android-хост, iOS-симулятор
./gradlew apiCheck                 # проверка совместимости ABI
./gradlew :sample-android:assembleDebug
./gradlew verifyLocalPublication   # публикует локально и проверяет состав артефактов
```

`verifyLocalPublication` — гейт, которого не даёт сама публикация: Gradle спокойно сообщает об
успехе для публикации без `-sources.jar`, а Maven Central такую не примет. Задача проверяет у
каждой публикации POM, module-дескриптор, непустые sources/javadoc и AAR или klib там, где он
нужен таргету. Пропавшая публикация тоже валит задачу — молча потерянный таргет не пройдёт.

Инструментальный набор Room выполняется на устройстве или эмуляторе — только там загружается
bundled-драйвер SQLite:

```bash
./gradlew :cache-store-room:connectedAndroidDeviceTest
```

В `verification/` лежат три одноразовые сборки, которые потребляют Kache так, как это делает
реальный пользователь — по координатам из `build/repo`, никогда через `project(...)`. Модульные
тесты внутри сборки эту границу не пересекают: неверная координата, POM без нужного поля или
спрятанная `api`-зависимость видны только здесь.

```bash
./gradlew -p verification/consumer run                      # JVM
./gradlew -p verification/consumer-android assembleDebug    # обычный Android, варианты AAR
./gradlew -p verification/consumer-kmp :app:compileKotlinJvm :app:compileKotlinIosArm64
./gradlew -p verification/consumer-kmp :iosx64:compileKotlinIosX64
```

`consumer-android` и `consumer-kmp` не объявляют serialization у себя, поэтому перестают
компилироваться в тот момент, когда `:cache-storage` перестаёт отдавать `KSerializer` как `api`.

Каждый вход сборки — jar, klib и дескриптор `.pom`/`.module` — сверяется с записанным SHA-256 из
`gradle/verification-metadata.xml`, поэтому обновление зависимости валит сборку, пока метаданные
не перегенерированы:

```bash
./gradlew --write-verification-metadata sha256 check :sample-android:assembleDebug
./gradlew --dependency-verification=strict check
```

Документационные артефакты — осознанное исключение. `-sources.jar`, `-javadoc.jar` и их варианты
`-samples-sources` / `-common-sources` доверяются по имени через `<trusted-artifacts>`: они никогда
не попадают в compile- или runtime-classpath, а их пининг был единственной частью этого файла,
которая не могла сойтись, — `--write-verification-metadata` записывает только то, что скачал сам
процесс, а IDE резолвит документационные варианты, которых не касается ни одна CLI-задача.
Бинарники, klib'ы и дескрипторы остаются запиненными; компромисс зафиксирован в самом файле рядом
с правилами.

Два события всё ещё требуют явной команды, и оба редкие:

- **Обновление Gradle wrapper.** Дистрибутив кладёт свои runtime-библиотеки в
  `gradle.gradleHomeDir/lib`, и IDE резолвит их алиасы для индексации, поэтому их дескрипторы
  должны быть запинены, хотя ни одна CLI-задача их не резолвит:

  ```bash
  ./gradlew --write-verification-metadata sha256 recordIdeIndexingArtifacts
  ```

  Задача читает координаты, которые сам дистрибутив пишет рядом с каждой библиотекой в
  `lib/*.properties` (`alias.group`/`alias.name`/`alias.version`), а не хардкодит их, поэтому
  обновление wrapper'а закрывается целым классом. Сами документационные файлы доверены и записи не
  требуют — именно это делает шаг одним, а не серией волн.
- **Обновление AGP.** `aapt2` выбирается артефакт-трансформом по ОС, поэтому каждой платформе, на
  которой вы собираете, нужен свой checksum: пиннинг только той ОС, на которой сгенерирован файл,
  уронил Linux-джобу CI, пока macOS оставался зелёным. Записаны все три варианта (`-linux`, `-osx`,
  `-windows`); новые значения считайте из артефактов, опубликованных в
  `dl.google.com/android/maven2`.

После обновления зависимости перегенерируйте файл целиком, а не дополняйте, и проверьте
результат с `--refresh-dependencies` — без него верификация проходит по кэшу и не доказывает
ничего.

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

`0.1.0`. Определено девять фаз плана: восемь реализованы, девятая (наблюдаемость)
не начата по решению. Лимиты размера, стратегии вытеснения и опциональная уборка
просроченных записей приехали вместе с ними, а аудит безопасности из тринадцати находок
закрыт (см. `SECURITY.md` и разбор аудита).

### Покрытие тестами

521 тест в стандартной матрице плюс 20 инструментальных, зелёные на каждом хосте:

| Модуль | JVM | Android-хост | iOS-симулятор | Android-устройство |
|---|---:|---:|---:|---:|
| `:cache-core` | 120 | 120 | 120 | — |
| `:cache-storage` | 66 | — | — | — |
| `:cache-store-room` | 23 | 11 | 20 | 20 |
| `:cache-android` | — | 41 | — | — |

`:cache-core` гоняет один и тот же набор `commonTest` на трёх хостах, поэтому число там совпадает
по построению. У `:cache-store-room` один общий контракт (`RoomStorageEngineTestBase`) и тонкие
подклассы на платформу, которые поставляют только фабрику, поэтому набор на устройстве проверяет
тот же контракт, что host и iOS.

**Известные пробелы, названные прямо:**

- **Android API не покрыт проверкой совместимости ABI.** BCV цепляется за Android-таргет
  KMP только когда его compilation назван `release`, а плагин AGP KMP даёт `main` (проверено
  вплоть до BCV 0.18.1), поэтому `:cache-android` публикует AAR без golden-файла. Пробел
  смягчён рефлексия-тестом `PublicApiTest`, который фиксирует публичную поверхность, но не
  устранён.
- `createFromContext` — фабрика Room, которую реально вызывают потребители — проверяется на
  настоящем устройстве набором `RoomStorageEngineAndroidDeviceTest`, где bundled-драйвер
  SQLite загружается по-настоящему. Host-набор так не может: JNI-библиотека драйвера собрана
  под Android ABI.
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
- **Существующий файловый кэш не читается после обновления.** Аудит заменил отображение
  ключа в имя файла: старое было необратимым, и два разных ключа могли схлопнуться в одну
  запись. Старые файлы не читаются и не мигрируют — они просто мёртвый груз в каталоге кэша,
  пока его не очистят. Для кэша это правильный размен, но дверь односторонняя.
- **`KacheException.ExpiredException` удалён.** Его никто не бросал, и это было единственное
  исключение, клавившее ключ кэша в `message` и `toString`. Просроченная запись — это промах,
  а не ошибка. Потребитель, который его ловил, не скомпилируется.
- Публикации в Maven Central пока нет. Оставшиеся ручные шаги (верификация пространства имён
  и Portal User Token) описаны в заметках о релизе.

## Лицензия

MIT. См. [LICENSE](LICENSE).
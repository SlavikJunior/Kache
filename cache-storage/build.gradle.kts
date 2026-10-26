plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    id("kache.publish")
}

kotlin {
    // Enforce explicit visibility modifiers for all public API
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "io.github.slavikjunior.kache.storage"
        compileSdk = 36
        minSdk = 23

        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = false
        }
    }

    // iOS-таргеты публикуются ради сериализаторов, а не ради движка: FileStorageEngine
    // построен на java.io и остаётся в jvmCommonMain. Без этого klib'а приложение на
    // iOS не может ни передать `KotlinxJsonSerializer` в `:cache-store-room`, ни
    // воспользоваться любым другим движком — ему пришлось бы заново реализовывать
    // сериализацию у себя, вместе с защитой от глубокого JSON.
    //
    // iosX64 не объявляется: Apple сняла его с поддержки.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // JVM и Android оба используют java.io, поэтому FileStorageEngine реализован один
        // раз в общем source set вместо двух копий.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)

        // Наборы создаются вручную, потому что ручные dependsOn выше всё равно отключают
        // шаблон иерархии для этого модуля. Файловый движок теперь общий (commonMain), а
        // iOS-реализация файловых операций лежит в iosMain и использует kotlinx-io.
        val iosMain by creating {
            dependsOn(commonMain.get())

            dependencies {
                // Only the iOS actual of FileOps uses it: the engine itself is common code, and
                // `implementation` keeps kotlinx-io off the consumer's compile classpath.
                implementation(libs.kotlinx.io.core)
            }
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)

        val iosTest by creating {
            dependsOn(commonTest.get())
        }
        iosArm64Test.get().dependsOn(iosTest)
        iosSimulatorArm64Test.get().dependsOn(iosTest)

        commonMain.dependencies {
            // FileStorageEngine implements StorageEngine, KacheSerializer is public
            api(project(":cache-core"))
            
            // Coroutines inherited transitively from :cache-core, but prefer explicit declaration
            implementation(libs.kotlinx.coroutines.core)

            // KSerializer appears in KotlinxJsonSerializer's public constructor, so it has to
            // stay on the consumer's compile classpath. `implementation` would hide it and turn
            // any `KotlinxJsonSerializer(User.serializer())` call into a compile error.
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            api(libs.kotlinx.serialization.json)
            implementation(project(":cache-core"))
        }
    }
}

// The Android compilation is not covered by the `jvm { compilerOptions }` block above and
// would otherwise target whatever JDK the Gradle daemon runs on. A Robolectric host test
// in a dependent module runs on JDK 17 (see :cache-store-room), so the bytecode it links
// against has to stay loadable there. Task-level configuration is used because the
// extension-level compilerOptions does not reach the AGP KMP Android compilation.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

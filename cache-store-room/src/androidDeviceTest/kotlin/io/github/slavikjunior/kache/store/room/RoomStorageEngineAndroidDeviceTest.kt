package io.github.slavikjunior.kache.store.room

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the shared [RoomStorageEngineTestBase] contract on a real device or emulator.
 *
 * This is the only suite where [androidx.sqlite.driver.bundled.BundledSQLiteDriver] actually
 * loads: its JNI library is compiled for Android ABIs and fails inside a host JVM, which is
 * why `RoomStorageEngineAndroidHostTest` builds its database with the framework driver
 * instead. `createFromContext` is therefore exercised here for real, on the same code path
 * a consumer gets from the published AAR.
 */
@RunWith(AndroidJUnit4::class)
class RoomStorageEngineAndroidDeviceTest : RoomStorageEngineTestBase() {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    override fun createEngine(): RoomStorageEngine =
        createFromContext(context, "kache-device-$runId-${counter.incrementAndGet()}.db")

    private companion object {
        /**
         * Unique per run: the emulator's cache directory survives between runs, and a fixed
         * name would make the second run read rows no test wrote. That is exactly how the
         * iOS suite failed before it was fixed.
         */
        private val runId = System.nanoTime().toString()
        private val counter = AtomicInteger()
    }
}

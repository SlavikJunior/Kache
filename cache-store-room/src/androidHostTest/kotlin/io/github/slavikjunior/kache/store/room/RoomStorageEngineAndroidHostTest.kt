package io.github.slavikjunior.kache.store.room

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs the Room engine against the Android runtime under Robolectric.
 *
 * The database is built directly with the framework driver rather than through
 * [createFromContext]. The bundled driver's JNI library is compiled for Android ABIs and
 * cannot load inside a host JVM test, so `createFromContext` is not exercised here at
 * runtime; it is covered by compilation, by the published AAR and by the sample app.
 */
@RunWith(RobolectricTestRunner::class)
class RoomStorageEngineAndroidHostTest {

    private lateinit var context: Context
    private lateinit var database: KacheDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.databaseBuilder<KacheDatabase>(
            context = context,
            name = "host-test.db",
        )
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun engine(): RoomStorageEngine = RoomStorageEngineFactory.create(database)

    private fun record(payload: String, ttl: Duration? = null): StorageRecord<*> =
        StorageRecord(value = byteArrayOf(), data = payload.encodeToByteArray(), createdAt = 0L, ttl = ttl)

    @Test
    fun theFactoryProducesAUsableEngine() = runBlocking {
        val engine = engine()

        assertNotNull(engine)
        assertEquals(0L, engine.size())
    }

    @Test
    fun theEngineRoundTripsThroughTheAndroidDatabase() = runBlocking {
        val engine = engine()

        engine.put("k", record("android-payload"))
        val read = engine.get("k")

        assertNotNull(read)
        assertEquals("android-payload", read.data.decodeToString())
        assertEquals(1L, engine.size())
    }

    @Test
    fun overwritingReplacesTheRecord() = runBlocking {
        val engine = engine()
        engine.put("k", record("first"))
        engine.put("k", record("second"))

        assertEquals(1L, engine.size())
        assertEquals("second", engine.get("k")?.data?.decodeToString())
    }

    @Test
    fun theEngineClearsEveryRecord() = runBlocking {
        val engine = engine()
        engine.put("a", record("1"))
        engine.put("b", record("2"))

        engine.clear()

        assertEquals(0L, engine.size())
        assertNull(engine.get("a"))
    }

    @Test
    fun removeIsSupportedOnAndroidToo() = runBlocking {
        val engine = engine()
        engine.put("k", record("1"))

        assertEquals(true, engine.remove("k"))
        assertEquals(false, engine.remove("k"))
    }

    @Test
    fun removeExpiredIsSupportedOnAndroidToo() = runBlocking {
        val engine = engine()
        engine.put("k", record("1", ttl = 10.milliseconds))

        assertEquals(1L, engine.removeExpired(now = 1_000L))
        assertNull(engine.get("k"))
    }

    @Test
    fun removeExpiredKeepsRecordsWithoutTtl() = runBlocking {
        val engine = engine()
        engine.put("forever", record("v"))

        assertEquals(0L, engine.removeExpired(now = Long.MAX_VALUE))
        assertEquals(1L, engine.size())
    }
}
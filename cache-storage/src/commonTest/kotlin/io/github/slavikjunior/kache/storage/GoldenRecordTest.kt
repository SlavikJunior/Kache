package io.github.slavikjunior.kache.storage

import io.github.slavikjunior.kache.core.StorageRecord
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Compatibility with what 0.1.0 wrote.
 *
 * A published release may be installed on devices whose cache directory is already full of
 * records. The byte format is therefore not an implementation detail this release may change:
 * a record the earlier engine wrote has to stay readable, or an upgrade silently throws away
 * every consumer's disk cache — no error, no migration, just a cold start.
 *
 * The bytes below were produced by 0.1.0's `FileStorageEngine` (`kgolden`, a 60-second record
 * holding `hello-kache`), captured before this release changed how the engine reaches the file
 * system, and pasted in verbatim. Do not regenerate them from the current code: that would
 * assert that today's writer agrees with today's reader, which is not the property at stake.
 */
class GoldenRecordTest {

    private val root = newTestDirectory("engine-golden")
    private val engine = FileStorageEngine(root)

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aRecordWrittenByRelease010IsStillReadable() = runTest {
        FileTestSupport.writeFile(joinPath(root, "kgolden"), GOLDEN_BYTES)

        val read = assertNotNull(
            engine.get("golden"),
            "a record from 0.1.0 must read back, not be treated as corrupt",
        )

        assertContentEquals("hello-kache".encodeToByteArray(), read.data)
        assertEquals(1_700_000_000_000L, read.createdAt)
        assertEquals(60.seconds, read.ttl)
        assertEquals(1_700_000_030_000L, read.lastAccessedAt)
    }

    @Test
    fun theGoldenRecordIsCountedAndRankedLikeAnyOther() = runTest {
        FileTestSupport.writeFile(joinPath(root, "kgolden"), GOLDEN_BYTES)

        assertEquals(1L, engine.size())
        assertEquals(
            listOf("golden"),
            engine.evictionCandidates(io.github.slavikjunior.kache.core.EvictionStrategy.LRU, limit = 10, now = 0L),
            "the key has to be recoverable from the file name the old engine chose",
        )
    }

    private companion object {
        /** `v2|1700000000000|60000|1700000030000|11|hello-kache`, as 0.1.0 wrote it. */
        val GOLDEN_BYTES: ByteArray
            @OptIn(ExperimentalEncodingApi::class)
            get() = Base64.decode("djJ8MTcwMDAwMDAwMDAwMHw2MDAwMHwxNzAwMDAwMDMwMDAwfDExfGhlbGxvLWthY2hl")
    }
}

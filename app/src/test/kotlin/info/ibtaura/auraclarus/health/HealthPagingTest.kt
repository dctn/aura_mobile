package info.ibtaura.auraclarus.health

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Paging through Health Connect reads stops when it should, so one sync can't use up the request quota. */
class HealthPagingTest {
    @Test
    fun anEmptyTokenEndsTheRead() {
        // Android 13's Health Connect app sends "" on the last page; reading on re-read page one until rate-limited.
        assertNull(HealthConnectReader.nextPageToken("", pageRecords = 0, total = 0, pages = 1))
        assertNull(HealthConnectReader.nextPageToken("", pageRecords = 12, total = 12, pages = 1))
        assertNull(HealthConnectReader.nextPageToken(null, pageRecords = 12, total = 12, pages = 1))
    }

    @Test
    fun anEmptyPageOrACapEndsTheRead() {
        assertNull(HealthConnectReader.nextPageToken("next", pageRecords = 0, total = 0, pages = 1))
        assertNull(HealthConnectReader.nextPageToken("next", pageRecords = 1000, total = 5000, pages = 5))
        assertNull(HealthConnectReader.nextPageToken("next", pageRecords = 10, total = 100, pages = 10))
    }

    @Test
    fun aRealTokenReadsTheNextPage() {
        assertEquals("next", HealthConnectReader.nextPageToken("next", pageRecords = 1000, total = 1000, pages = 1))
    }
}

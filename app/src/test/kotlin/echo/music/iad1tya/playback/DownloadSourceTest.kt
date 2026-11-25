package echo.music.iad1tya.playback

import echo.music.iad1tya.constants.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadSourceTest {
  @Test
  fun defaultQualityUsesTheExistingCacheKey() {
    val existing = DownloadSource("track123", DownloadQuality.YOUTUBE)

    assertEquals("track123", existing.newCacheKey())
    assertEquals(existing, DownloadSource.fromCacheKey("track123"))
  }

  @Test
  fun newRequestsPreserveTheChosenQualityAndCannotReuseOlderBytes() {
    val mediaId = "addon_source_track:version"
    val lossless = DownloadSource(mediaId, DownloadQuality.LOSSLESS)
    val compressed = DownloadSource(mediaId, DownloadQuality.HIGH)
    val firstLosslessKey = lossless.newCacheKey()
    val retryKey = lossless.newCacheKey()
    val compressedKey = compressed.newCacheKey()

    assertEquals(lossless, DownloadSource.fromCacheKey(firstLosslessKey))
    assertEquals(lossless, DownloadSource.fromCacheKey(retryKey))
    assertEquals(compressed, DownloadSource.fromCacheKey(compressedKey))
    assertNotEquals(mediaId, firstLosslessKey)
    assertNotEquals(firstLosslessKey, retryKey)
    assertNotEquals(firstLosslessKey, compressedKey)
  }

  @Test
  fun aMalformedQualityMarkerDoesNotIdentifyLegacyBytesAsLossless() {
    val key = "hrmusic-download:LOSSLESS:legacy:track123"

    assertEquals(DownloadSource(key, DownloadQuality.YOUTUBE), DownloadSource.fromCacheKey(key))
  }
}

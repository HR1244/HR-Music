package echo.music.iad1tya.playback

import androidx.media3.common.C
import com.music.echo.extensions.AddonStreamQuality
import com.music.echo.extensions.AudioSourceQuality
import com.music.innertube.models.response.PlayerResponse
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.utils.YTPlayerUtils.PlaybackData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQualityPolicyTest {
  private val aac320 = AudioSourceQuality.from("audio/mp4", "mp4a.40.2", 320_000)
  private val opus128 = AudioSourceQuality.from("audio/webm", "opus", 128_000)

  @Test
  fun `strict lossless requires a known lossless codec`() {
    val policy = PlaybackQualityPolicy(AudioQuality.LOSSLESS, allowDowngrade = false)

    assertTrue(policy.accepts(AudioSourceQuality.from("audio/flac")))
    assertTrue(policy.accepts(AudioSourceQuality.from("audio/mp4", "alac")))
    assertFalse(policy.accepts(aac320))
    assertFalse(policy.accepts(AudioSourceQuality.from("audio/x-wav")))
    assertFalse(policy.accepts(AudioSourceQuality.from("audio/flac", "aac")))
    assertFalse(policy.allowsYouTube)
  }

  @Test
  fun `lossless can use a lower source when downgrade is enabled`() {
    val policy = PlaybackQualityPolicy(AudioQuality.LOSSLESS, allowDowngrade = true)

    assertTrue(policy.accepts(opus128))
    assertTrue(policy.allowsYouTube)
  }

  @Test
  fun `only a verified bitrate tier satisfies strict high quality`() {
    val policy = PlaybackQualityPolicy(AudioQuality.HIGH, allowDowngrade = false)

    assertTrue(policy.accepts(aac320))
    assertFalse(policy.accepts(opus128))
    assertFalse(policy.accepts(aac320.copy(bitrate = null)))
  }

  @Test
  fun `strict Opus requires an Opus stream`() {
    val policy = PlaybackQualityPolicy(AudioQuality.OPUS, allowDowngrade = false)

    assertTrue(policy.accepts(opus128))
    assertFalse(policy.accepts(aac320.copy(bitrate = 128_000)))
  }

  @Test
  fun `Medium requests the addon's compatible modes while strict Lossless requests only Lossless`() {
    val medium = PlaybackQualityPolicy(AudioQuality.MEDIUM, allowDowngrade = false)
    val lossless = PlaybackQualityPolicy(AudioQuality.LOSSLESS, allowDowngrade = false)

    assertEquals(listOf(AddonStreamQuality.HIGH, AddonStreamQuality.LOW), medium.addonQualities)
    assertEquals(listOf(AddonStreamQuality.LOSSLESS), lossless.addonQualities)
    assertTrue(medium.accepts(opus128))
    assertFalse(medium.accepts(aac320))
  }

  @Test
  fun `strict Medium tries AAC when the primary rendition exceeds the selected tier`() = runTest {
    val policy = PlaybackQualityPolicy(AudioQuality.MEDIUM, allowDowngrade = false)
    val high = playback("audio/webm; codecs=opus", 256_000)
    val medium = playback("audio/mp4; codecs=mp4a.40.2", 128_000)
    val requests = mutableListOf<Boolean>()

    val selected = policy.resolveYouTubePlayback { requireM4a ->
      requests += requireM4a
      Result.success(if (requireM4a) medium else high)
    }

    assertEquals(listOf(false, true), requests)
    assertSame(medium, selected)
  }

  @Test
  fun `strict Medium still rejects an AAC rendition below its tier`() = runTest {
    val policy = PlaybackQualityPolicy(AudioQuality.MEDIUM, allowDowngrade = false)
    val high = playback("audio/webm; codecs=opus", 256_000)
    val low = playback("audio/mp4; codecs=mp4a.40.2", 64_000)

    assertNull(policy.resolveYouTubePlayback { Result.success(if (it) low else high) })
  }

  @Test
  fun `lossless startup caps the target at the remaining duration`() {
    assertEquals(60_000_000L, losslessStartupTargetUs(C.TIME_UNSET, 0))
    assertEquals(60_000_000L, losslessStartupTargetUs(180_000_000, 20_000_000))
    assertEquals(25_000_000L, losslessStartupTargetUs(50_000_000, 25_000_000))
    assertEquals(0L, losslessStartupTargetUs(20_000_000, 21_000_000))
  }

  private fun playback(mimeType: String, bitrate: Int): PlaybackData = PlaybackData(
    audioConfig = null,
    videoDetails = null,
    playbackTracking = null,
    format = PlayerResponse.StreamingData.Format(
      itag = 0,
      url = null,
      mimeType = mimeType,
      bitrate = bitrate,
      width = null,
      height = null,
      contentLength = null,
      quality = "medium",
      fps = null,
      qualityLabel = null,
      averageBitrate = bitrate,
      audioQuality = null,
      approxDurationMs = null,
      audioSampleRate = null,
      audioChannels = null,
      loudnessDb = null,
      lastModified = null,
      signatureCipher = null,
      cipher = null,
      audioTrack = null,
    ),
    streamUrl = "https://example.com/audio",
    streamExpiresInSeconds = 60,
  )
}

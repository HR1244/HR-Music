package echo.music.iad1tya.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferPolicyTest {
  @Test
  fun `startup waits for less audio on a fast connection`() {
    assertEquals(3_000_000L, losslessRunwayUs(5_000_000, 1_000_000, 1f, false, false))
    assertEquals(15_000_000L, losslessRunwayUs(800_000, 1_000_000, 1f, false, false))
  }

  @Test
  fun `restricted networks cap media buffered before startup and recovery`() {
    assertEquals(10_000_000L, losslessRunwayUs(800_000, 1_000_000, 1f, false, true))
    assertEquals(7_000_000L, losslessRunwayUs(800_000, 1_000_000, 1f, true, true))
  }

  @Test
  fun `faster playback requires more media data for a given runway`() {
    assertEquals(6_000_000L, losslessRunwayUs(5_000_000, 1_000_000, 2f, false, false))
    assertTrue(estimatedLosslessBitrate(96_000, 2) > estimatedLosslessBitrate(44_100, 2))
  }

  @Test
  fun `startup near the end needs only the remaining audio`() {
    val targetUs = 9_000_000L
    assertEquals(targetUs, capToRemainingAudioUs(targetUs, C.TIME_UNSET, 0))
    assertEquals(targetUs, capToRemainingAudioUs(targetUs, 60_000_000, -1))
    assertEquals(3_000_000L, capToRemainingAudioUs(targetUs, 50_000_000, 47_000_000))
    assertEquals(0L, capToRemainingAudioUs(targetUs, 20_000_000, 21_000_000))
  }

  @Test
  fun `a long wait lowers the startup target only after useful data is buffered`() {
    assertTrue(losslessBufferReady(6_000_000, 6_000_000, 200, 1f, false))
    assertFalse(losslessBufferReady(3_000_000, 9_000_000, 7_999, 1f, false))
    assertTrue(losslessBufferReady(3_000_000, 9_000_000, 8_000, 1f, false))
    assertFalse(losslessBufferReady(3_000_000, 9_000_000, 12_000, 1f, true))
    assertTrue(losslessBufferReady(4_000_000, 9_000_000, 12_000, 1f, true))
  }
}

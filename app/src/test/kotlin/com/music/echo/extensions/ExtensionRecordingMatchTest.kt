package com.music.echo.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExtensionRecordingMatchTest {
  private val manager = ExtensionManager(RuntimeEnvironment.getApplication())

  @Test
  fun `fallback can match the F1 recording through standard audio title decoration`() {
    val soundtrack = AddonTrack("correct", "F1 - Official Audio", "Hans Zimmer", 181)
    val candidates = listOf(
      AddonTrack("wrong-artist", "F1", "Another Artist", 180),
      AddonTrack("remix", "F1 Remix", "Hans Zimmer", 180),
      soundtrack,
    )

    assertEquals(soundtrack, manager.selectBestMatch(candidates, "F1", "Hans Zimmer", 180_000, true))
  }

  @Test
  fun `fallback rejects an uncertain source match`() {
    val candidates = listOf(
      AddonTrack("wrong-artist", "F1", "Hans Example", 180),
      AddonTrack("different-duration", "F1", "Hans Zimmer", 215),
      AddonTrack("different-title", "F1 Race Theme", "Hans Zimmer", 180),
    )

    assertNull(manager.selectBestMatch(candidates, "F1", "Hans Zimmer", 180_000, true))
  }
}

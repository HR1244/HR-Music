package echo.music.iad1tya.generate

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import echo.music.iad1tya.db.entities.RecommendationExclusionEntity
import echo.music.iad1tya.db.entities.SongPlayStatsEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AutoplayTasteRankingTest {
  @Test
  fun ranksCompletedPlaysAndKeepsOneOffSkipsWhileHonoringExclusions() = runTest {
    val stats = FakeSongPlayStatsDao()
    val exclusions = FakeRecommendationExclusionDao()
    val engine = LocalTasteEngine(stats, exclusions)
    val discovery = item("discovery")
    val skippedOnce = item("one skip")
    val skippedOften = item("many skips")
    val favorite = item("favorite")
    val blocked = item("blocked")

    stats.upsert(SongPlayStatsEntity(trackKey = "one skip|artist", skipCount = 1))
    stats.upsert(SongPlayStatsEntity(trackKey = "many skips|artist", skipCount = 4))
    stats.upsert(SongPlayStatsEntity(trackKey = "favorite|artist", playCount = 4, totalPlayTimeMs = 600_000L))
    exclusions.upsert(RecommendationExclusionEntity(trackKey = "blocked|artist", excludedAtMillis = 1L))

    assertEquals(
      listOf(favorite, discovery, skippedOnce),
      engine.rankAutoplay(listOf(blocked, skippedOften, discovery, skippedOnce, favorite, discovery)),
    )
    assertEquals(listOf(skippedOften), engine.rankAutoplay(listOf(blocked, skippedOften)))
    assertEquals(emptyList<MediaItem>(), engine.rankAutoplay(listOf(blocked)))
  }

  private fun item(title: String): MediaItem = MediaItem.Builder()
    .setMediaId(title)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Artist").build())
    .build()
}

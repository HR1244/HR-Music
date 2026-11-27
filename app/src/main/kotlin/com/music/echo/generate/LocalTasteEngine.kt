package echo.music.iad1tya.generate

import androidx.media3.common.MediaItem
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.daos.RecommendationExclusionDao
import echo.music.iad1tya.db.daos.SongPlayStatsDao
import echo.music.iad1tya.db.entities.PlaylistEntity
import echo.music.iad1tya.db.entities.PlaylistSongMap
import echo.music.iad1tya.db.entities.SongPlayStatsEntity
import echo.music.iad1tya.extensions.metadata
import echo.music.iad1tya.models.toMediaMetadata
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber

internal const val MAX_SUGGESTIONS = 25
internal const val PLAY_WEIGHT = 2.0f
internal const val COMPLETED_PLAY_WEIGHT = 2.0f
internal const val SKIP_PENALTY = 3.0f
internal const val LIKED_BONUS = 5.0f
internal const val RECENCY_BONUS = 1.5f
internal const val TIME_OF_DAY_BONUS = 1.3f
private const val DISCOVERY_SCORE = 2.0f
private const val ARTIST_REPEAT_PENALTY = 1.5f
private const val EXTRA_DISCOVERY_CANDIDATES = 15

private val MORNING = 6..11
private val AFTERNOON = 12..17
private val EVENING = 18..21

internal fun currentTimeOfDay(hour: Int = LocalTime.now().hour): String = when (hour) {
  in MORNING -> "morning"
  in AFTERNOON -> "afternoon"
  in EVENING -> "evening"
  else -> "night"
}

internal fun scoreStats(
  stats: SongPlayStatsEntity,
  likedKeys: Set<String>,
  timeOfDay: String,
): Float {
  val isLiked = stats.trackKey in likedKeys
  val isRecent = System.currentTimeMillis() - stats.lastPlayedAtMillis < 7L * 86_400_000L

  val playMinutes = (stats.totalPlayTimeMs.coerceAtLeast(0L) / 60_000.0)
  val normalizedPlayTime = kotlin.math.ln(1.0 + playMinutes).toFloat()
  val completedPlays = stats.playCount.coerceAtLeast(0)
  // A single skip is ambiguous. Completed plays offset later skips of a familiar track.
  val repeatedSkips = (stats.skipCount.coerceAtLeast(0) - maxOf(completedPlays, 1)).coerceIn(0, 3)
  var score = normalizedPlayTime * PLAY_WEIGHT
  score += kotlin.math.ln(1.0 + completedPlays).toFloat() * COMPLETED_PLAY_WEIGHT
  score -= repeatedSkips * SKIP_PENALTY
  if (isLiked) score += LIKED_BONUS
  if (isRecent) score += RECENCY_BONUS

  val hourBonus = when (timeOfDay) {
    "morning" -> if (isRecent) TIME_OF_DAY_BONUS else 1.0f
    "night" -> if (isLiked) TIME_OF_DAY_BONUS else 1.0f
    else -> 1.0f
  }
  score *= hourBonus
  return score.coerceAtLeast(0f)
}

internal fun applyCandidateFilter(stats: SongPlayStatsEntity, isLiked: Boolean = false): Boolean =
  isLiked || stats.skipCount < 2 || stats.playCount > 0 || stats.totalPlayTimeMs > 45_000L

internal fun scoreTasteCandidate(
  key: String,
  stats: SongPlayStatsEntity?,
  likedKeys: Set<String>,
  timeOfDay: String,
): Float {
  if (stats == null) return if (key in likedKeys) LIKED_BONUS else DISCOVERY_SCORE
  val score = scoreStats(stats, likedKeys, timeOfDay)
  val isNewToListener = stats.playCount == 0 && stats.skipCount <= 1 && stats.totalPlayTimeMs < 30_000L
  return if (isNewToListener) maxOf(score, DISCOVERY_SCORE) else score
}

private data class ScoredCandidate<T>(val item: T, val score: Float, val artist: String)

internal fun <T> rankWithArtistVariety(
  candidates: List<T>,
  limit: Int = candidates.size,
  primaryArtist: (T) -> String,
  score: (T) -> Float,
): List<T> {
  val remaining = candidates.map {
    ScoredCandidate(it, score(it), primaryArtist(it).trim().lowercase())
  }.toMutableList()
  val selectedPerArtist = mutableMapOf<String, Int>()
  return buildList {
    repeat(limit.coerceIn(0, remaining.size)) {
      val nextIndex = remaining.indices.maxBy { index ->
        val candidate = remaining[index]
        val repeats = (selectedPerArtist[candidate.artist] ?: 0).coerceAtMost(3)
        candidate.score - repeats * ARTIST_REPEAT_PENALTY
      }
      val next = remaining.removeAt(nextIndex)
      add(next.item)
      if (next.artist.isNotEmpty()) {
        selectedPerArtist[next.artist] = (selectedPerArtist[next.artist] ?: 0) + 1
      }
    }
  }
}

@Singleton
open class LocalTasteEngine @Inject constructor(
  private val songPlayStatsDao: SongPlayStatsDao,
  private val recommendationExclusionDao: RecommendationExclusionDao,
  private val database: MusicDatabase? = null,
) {
  /** Rank only automatically suggested items. Honor explicit exclusions even when none remain. */
  internal suspend fun rankAutoplay(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
    if (items.isEmpty()) return@withContext emptyList()

    data class Candidate(val item: MediaItem, val key: String, val artist: String)

    val candidates = items.distinctBy { it.mediaId }.map { item ->
      val metadata = item.metadata
      val title = metadata?.title ?: item.mediaMetadata.title?.toString().orEmpty()
      val artist = metadata?.artists?.joinToString(", ") { it.name }
        ?: item.mediaMetadata.artist?.toString().orEmpty()
      val primaryArtist = metadata?.artists?.firstOrNull()?.name ?: artist.substringBefore(", ")
      Candidate(item, trackKeyOf(title, artist), primaryArtist)
    }

    var eligible = candidates
    try {
      val exclusions = recommendationExclusionDao.getAll().map { it.trackKey }.toSet()
      eligible = candidates.filterNot { it.key in exclusions }
      if (eligible.isEmpty()) return@withContext emptyList()

      val likedKeys = likedTrackKeys()
      val statsByKey = songPlayStatsDao.findByKeys(eligible.map { it.key }.distinct()).associateBy { it.trackKey }
      val suitable = eligible.filter { candidate ->
        statsByKey[candidate.key]?.let { applyCandidateFilter(it, candidate.key in likedKeys) } != false
      }
      val timeOfDay = currentTimeOfDay()
      // Keep the queue moving if the provider had no other suggestions.
      rankWithArtistVariety(suitable.ifEmpty { eligible }, primaryArtist = { it.artist }) {
        scoreTasteCandidate(it.key, statsByKey[it.key], likedKeys, timeOfDay)
      }.map { it.item }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.d(e, "Autoplay taste signals unavailable")
      eligible.map { it.item }
    }
  }

  protected suspend fun likedTrackKeys(): Set<String> =
    database?.likedSongsByRowIdAsc()?.firstOrNull().orEmpty().map { song ->
      trackKeyOf(song.song.title, song.artists.joinToString(", ") { it.name })
    }.toSet()

  open suspend fun generate(count: Int = MAX_SUGGESTIONS): Result<String> = withContext(Dispatchers.IO) {
    try {
      val exclusions = recommendationExclusionDao.getAll().map { it.trackKey }.toSet()
      val timeOfDay = currentTimeOfDay()

      val now = System.currentTimeMillis()
      val thirtyDaysAgo = now - 30L * 86_400_000L

      val recentStats = songPlayStatsDao.mostPlayedSince(thirtyDaysAgo, limit = 100)
        .ifEmpty { songPlayStatsDao.recentlyPlayed(limit = 20) }

      if (recentStats.isEmpty()) {
        return@withContext Result.failure(IllegalStateException("No listening history available yet"))
      }

      val likedKeys = likedTrackKeys()

      val eligibleSeeds = recentStats
        .filter { it.trackKey !in exclusions && applyCandidateFilter(it, it.trackKey in likedKeys) }
        .sortedByDescending { scoreStats(it, likedKeys, timeOfDay) }

      if (eligibleSeeds.isEmpty()) {
        return@withContext Result.failure(IllegalStateException("All candidate tracks have been excluded"))
      }

      val seedVideoIds = eligibleSeeds.mapNotNull { it.videoId }.distinct().take(5)

      val expandedSongs = mutableListOf<SongItem>()
      val seenKeys = mutableSetOf<String>()
      seenKeys.addAll(exclusions)

      for (videoId in seedVideoIds) {
        val nextResult = runCatching {
          YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()
        }.getOrNull()

        val candidateSongs = mutableListOf<SongItem>()
        nextResult?.items?.filterIsInstance<SongItem>()?.let { candidateSongs.addAll(it) }

        nextResult?.relatedEndpoint?.let { endpoint ->
          runCatching {
            YouTube.related(endpoint).getOrNull()?.songs
          }.getOrNull()?.let { candidateSongs.addAll(it) }
        }

        for (songItem in candidateSongs) {
          val key = trackKeyOf(songItem.title, songItem.artists.joinToString(", ") { it.name })
          if (key !in seenKeys) {
            seenKeys.add(key)
            expandedSongs.add(songItem)
          }
        }
        if (expandedSongs.size - EXTRA_DISCOVERY_CANDIDATES >= count) break
      }

      val statsByKey = songPlayStatsDao.findByKeys(expandedSongs.map { it.tasteKey }).associateBy { it.trackKey }
      val eligibleSongs = expandedSongs.filter { song ->
        val key = song.tasteKey
        statsByKey[key]?.let { applyCandidateFilter(it, key in likedKeys) } != false
      }
      val rankedSongs = rankWithArtistVariety(eligibleSongs, count, primaryArtist = { it.leadArtist }) { song ->
        val key = song.tasteKey
        scoreTasteCandidate(key, statsByKey[key], likedKeys, timeOfDay)
      }
      val finalSongs = if (eligibleSongs.size >= 5) {
        rankedSongs
      } else {
        val localFallbackItems = eligibleSeeds
          .filter { it.videoId != null }
          .take(count)
          .map { stats ->
            SongItem(
              id = stats.videoId!!,
              title = stats.title,
              artists = listOf(com.music.innertube.models.Artist(name = stats.artist, id = null)),
              album = null,
              duration = null,
              thumbnail = stats.artworkUrl.orEmpty(),
            )
          }
        (rankedSongs + localFallbackItems).distinctBy { it.id }.take(count)
      }

      if (finalSongs.isEmpty()) {
        return@withContext Result.failure(IllegalStateException("Could not generate playlist from seeds"))
      }

      val playlistId = "local_taste_daily_mix"
      val playlistName = "My Taste Mix"

      persistPlaylist(playlistId, playlistName, finalSongs)

      Result.success(playlistId)
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Timber.e(e, "LocalTasteEngine generation failed")
      Result.failure(e)
    }
  }

  protected suspend fun persistPlaylist(
    playlistId: String,
    playlistName: String,
    songs: List<SongItem>,
  ) {
    database?.withTransaction {
      clearPlaylist(playlistId)
      insert(
        PlaylistEntity(
          id = playlistId,
          name = playlistName,
          browseId = null,
          bookmarkedAt = java.time.LocalDateTime.now(),
          isLocal = true,
          isEditable = true
        )
      )

      songs.forEachIndexed { index, songItem ->
        insert(songItem.toMediaMetadata())
        insert(
          PlaylistSongMap(
            playlistId = playlistId,
            songId = songItem.id,
            position = index,
          )
        )
      }
    }
  }
}

internal val SongItem.tasteKey: String
  get() = trackKeyOf(title, artists.joinToString(", ") { it.name })

internal val SongItem.leadArtist: String
  get() = artists.firstOrNull()?.name.orEmpty()

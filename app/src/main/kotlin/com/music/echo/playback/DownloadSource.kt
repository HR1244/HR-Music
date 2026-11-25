package echo.music.iad1tya.playback

import echo.music.iad1tya.constants.DownloadQuality
import java.util.UUID

internal data class DownloadSource(
  val mediaId: String,
  val quality: DownloadQuality,
) {
  fun newCacheKey(): String =
    if (quality == DownloadQuality.YOUTUBE) mediaId
    else "$PREFIX:${quality.name}:${UUID.randomUUID()}:$mediaId"

  companion object {
    private const val PREFIX = "hrmusic-download"

    fun fromCacheKey(key: String): DownloadSource {
      val parts = key.split(':', limit = 4)
      if (parts.size == 4 && parts[0] == PREFIX) {
        val quality = DownloadQuality.entries.firstOrNull { it.name == parts[1] }
        val attempt = runCatching { UUID.fromString(parts[2]) }.getOrNull()
        if (quality != null && quality != DownloadQuality.YOUTUBE &&
          attempt?.toString() == parts[2] && parts[3].isNotEmpty()
        ) {
          return DownloadSource(parts[3], quality)
        }
      }
      return DownloadSource(key, DownloadQuality.YOUTUBE)
    }
  }
}

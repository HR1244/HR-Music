package echo.music.iad1tya.playback

import com.music.echo.extensions.AddonStreamQuality
import com.music.echo.extensions.AudioSourceQuality
import com.music.innertube.models.response.PlayerResponse
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.utils.YTPlayerUtils.PlaybackData
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal data class PlaybackQualityPolicy(
  val requested: AudioQuality,
  val allowDowngrade: Boolean,
) {
  val requiresCertainQuality: Boolean
    get() = !allowDowngrade && requested != AudioQuality.AUTO

  val allowsYouTube: Boolean
    get() = !requiresCertainQuality || requested != AudioQuality.LOSSLESS

  val addonQualities: List<AddonStreamQuality>
    get() = when (requested) {
      AudioQuality.LOW -> listOf(AddonStreamQuality.LOW)
      AudioQuality.MEDIUM, AudioQuality.OPUS -> listOf(AddonStreamQuality.HIGH, AddonStreamQuality.LOW)
      AudioQuality.HIGH -> if (allowDowngrade) listOf(AddonStreamQuality.HIGH, AddonStreamQuality.LOW)
        else listOf(AddonStreamQuality.HIGH)
      AudioQuality.LOSSLESS -> if (allowDowngrade) AddonStreamQuality.entries
        else listOf(AddonStreamQuality.LOSSLESS)
      AudioQuality.AUTO -> AddonStreamQuality.entries
    }

  fun accepts(source: AudioSourceQuality): Boolean {
    if (requested == AudioQuality.AUTO) return true
    if (source.isLossless) return requested == AudioQuality.LOSSLESS
    if (requested == AudioQuality.LOSSLESS) return allowDowngrade
    if (requested == AudioQuality.OPUS && source.codec != "opus") return allowDowngrade
    val bitrate = source.bitrate ?: return allowDowngrade
    if (bitrate > requested.maxKbps * 1000) return false
    if (!requiresCertainQuality) return true
    return when (requested) {
      AudioQuality.LOW -> true
      AudioQuality.MEDIUM, AudioQuality.OPUS -> bitrate >= 96_000
      AudioQuality.HIGH -> bitrate >= 256_000
      else -> false
    }
  }

  suspend fun resolveYouTubePlayback(
    resolve: suspend (requireM4a: Boolean) -> Result<PlaybackData>,
  ): PlaybackData? {
    if (!allowsYouTube) return null

    suspend fun fetch(requireM4a: Boolean): PlaybackData? = resolve(requireM4a).getOrElse { error ->
      if (error is CancellationException || error is IOException || !requiresCertainQuality) throw error
      null
    }

    val first = fetch(false)
    if (first != null && (!requiresCertainQuality || accepts(first.format.sourceQuality))) return first
    // The extractor's Auto mode can return an audio tier outside Medium. Ask for the AAC
    // rendition of the same track and still apply the selected bitrate bounds.
    if (requested != AudioQuality.MEDIUM) return null
    return fetch(true)?.takeIf { accepts(it.format.sourceQuality) }
  }
}

internal val PlayerResponse.StreamingData.Format.sourceQuality: AudioSourceQuality
  get() = AudioSourceQuality.from(mimeType, bitrate = averageBitrate ?: bitrate)

internal class SelectedQualityUnavailable(message: String) : IllegalStateException(message)

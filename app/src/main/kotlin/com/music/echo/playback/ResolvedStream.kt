package echo.music.iad1tya.playback

import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import echo.music.iad1tya.utils.InnerTubeXResolver
import echo.music.iad1tya.utils.PlayerClient
import echo.music.iad1tya.utils.YTPlayerUtils.PlaybackData
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class ResolvedStream(
  val url: String,
  val expiresAtMs: Long,
  val headers: Map<String, String>,
  val isFromExtension: Boolean = false,
  val playbackData: PlaybackData? = null,
) {
  private val playbackUsed = AtomicBoolean()
  private val streamFingerprint by lazy {
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
  }

  fun cacheKey(mediaId: String): String = "${cacheKeyPrefix(mediaId)}$streamFingerprint"

  fun firstPlayback(): Boolean = playbackUsed.compareAndSet(false, true)

  fun resolve(dataSpec: DataSpec): DataSpec =
    dataSpec.buildUpon()
      .setUri(url.toUri())
      .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
      .build()

  companion object {
    fun cacheKeyPrefix(mediaId: String): String = "$mediaId:stream:v2:"

    fun from(
      playbackData: PlaybackData,
      nowMs: Long = System.currentTimeMillis(),
      isFromExtension: Boolean = false,
    ): ResolvedStream =
      ResolvedStream(
        url = playbackData.streamUrl,
        expiresAtMs = nowMs + playbackData.streamExpiresInSeconds * 1000L,
        headers = playbackData.headers
          ?: InnerTubeXResolver.headersFor(playbackData.streamUrl)
          ?: PlayerClient.forStreamUrl(playbackData.streamUrl).mediaHeaders(),
        isFromExtension = isFromExtension,
        playbackData = playbackData,
      )
  }
}

package echo.music.iad1tya.echomusic.updater

import java.net.URI

internal val releaseTagPattern = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")
private val updateApkPattern = Regex("HR-Music-Universal-GMS-([1-9][0-9]*)\\.apk")

internal data class UpdateAsset(val name: String, val url: String, val versionCode: Long)

internal fun parseUpdateAsset(repository: String, tag: String, url: String): UpdateAsset? {
  if (!releaseTagPattern.matches(tag)) return null
  val uri = runCatching { URI(url) }.getOrNull() ?: return null
  if (
    uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
      uri.userInfo != null || uri.query != null || uri.fragment != null
  ) return null
  val path = uri.rawPath ?: return null
  val prefix = "/$repository/"
  if (!path.startsWith(prefix, ignoreCase = true)) return null
  val releasePath = "releases/download/$tag/"
  val assetPath = path.drop(prefix.length)
  if (!assetPath.startsWith(releasePath)) return null
  val name = assetPath.drop(releasePath.length)
  val code = updateApkPattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
  return UpdateAsset(name, url, code.toLong())
}

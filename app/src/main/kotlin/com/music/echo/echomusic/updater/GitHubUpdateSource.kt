package echo.music.iad1tya.echomusic.updater

import echo.music.iad1tya.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.IOException
import org.json.JSONObject

internal val updateRepository: String?
  get() = BuildConfig.HR_MUSIC_UPDATE_REPOSITORY.takeIf { !BuildConfig.DEBUG && it.isNotEmpty() }

private val releaseClient by lazy {
  HttpClient(OkHttp) {
    followRedirects = false
    install(HttpTimeout) {
      requestTimeoutMillis = 15_000
      connectTimeoutMillis = 15_000
      socketTimeoutMillis = 15_000
    }
  }
}

internal suspend fun fetchGitHubRelease(tag: String? = null): JSONObject? {
  val repository = updateRepository ?: return null
  val releasePath = when {
    tag == null -> "latest"
    releaseTagPattern.matches(tag) -> "tags/$tag"
    else -> return null
  }
  val response = releaseClient.get("https://api.github.com/repos/$repository/releases/$releasePath") {
    header(HttpHeaders.Accept, "application/vnd.github+json")
  }
  if (response.status == HttpStatusCode.NotFound) return null
  if (response.status != HttpStatusCode.OK) throw IOException("GitHub returned ${response.status}")
  val release = JSONObject(response.bodyAsText())
  if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
  return release
}

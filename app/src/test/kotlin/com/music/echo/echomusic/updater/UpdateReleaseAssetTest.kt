package echo.music.iad1tya.echomusic.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReleaseAssetTest {
  private val repository = "Owner/HR-Music"
  private val tag = "v1.4.8"
  private val baseUrl = "https://github.com/$repository/releases/download/$tag"

  @Test
  fun acceptsOnlyTheUniversalGmsReleaseAsset() {
    val name = "HR-Music-Universal-GMS-169.apk"
    val url = "$baseUrl/$name"
    assertEquals(UpdateAsset(name, url, 169), parseUpdateAsset(repository, tag, url))
    assertNull(parseUpdateAsset(repository, tag, "$baseUrl/HR-Music-arm64-GMS-169.apk"))
    assertNull(parseUpdateAsset(repository, tag, "$baseUrl/HR-Music-Universal-GMS-debug.apk"))
    assertNull(parseUpdateAsset(repository, tag, "$baseUrl/HR-Music-Universal-GMS-0.apk"))
  }

  @Test
  fun requiresTheConfiguredRepositoryAndMatchingTag() {
    val name = "HR-Music-Universal-GMS-169.apk"
    assertNull(parseUpdateAsset(repository, tag, "$baseUrl/$name?download=1"))
    assertNull(parseUpdateAsset(repository, "v1.4.9", "$baseUrl/$name"))
    assertNull(parseUpdateAsset(repository, tag, "$baseUrl/other/$name"))
    assertNull(parseUpdateAsset(repository, tag, "https://github.com/EchoMusicApp/Echo-Music/releases/download/$tag/$name"))
  }
}

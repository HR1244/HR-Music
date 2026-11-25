package com.music.echo.extensions

import android.net.Uri
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal data class HostedAddonImport private constructor(
  val provider: Provider,
  val baseUrl: String,
) {
  enum class Provider(val displayName: String) {
    CLOUDFLARE("Cloudflare"),
    VERCEL("Vercel"),
  }

  companion object {
    private val sharedUrl = Regex("""(?i)(?<![\w:/])https://[^\s<>"']+""")
    private val cloudflareHost = Regex("""[a-z0-9-]{1,63}\.[a-z0-9-]{1,63}\.workers\.dev""")
    private val vercelHost = Regex("""[a-z0-9-]{1,63}\.vercel\.app""")
    private const val VERCEL_CALLBACK_PATH = "/extensions/vercel"

    fun fromSharedText(text: CharSequence): HostedAddonImport? =
      sharedUrl.findAll(text)
        .mapNotNull { fromUrl(it.value) }
        .distinct()
        .take(2)
        .singleOrNull()

    fun fromUrl(url: String, allowManifest: Boolean = true): HostedAddonImport? {
      val uri = parseUri(url) ?: return null
      val host = uri.httpsHost() ?: return null
      if (uri.rawQuery != null || uri.rawFragment != null) return null
      when (uri.rawPath) {
        "", "/" -> Unit
        "/manifest.json" -> if (!allowManifest) return null
        else -> return null
      }
      val provider =
        when {
          cloudflareHost.matches(host) -> Provider.CLOUDFLARE
          vercelHost.matches(host) -> Provider.VERCEL
          else -> return null
        }
      return HostedAddonImport(provider, "https://$host")
    }

    fun isVercelCallback(uri: Uri, callbackHost: String): Boolean {
      if (callbackHost.isEmpty()) return false
      val address = parseUri(uri.toString()) ?: return false
      return address.httpsHost()?.equals(callbackHost, ignoreCase = true) == true &&
        address.rawPath == VERCEL_CALLBACK_PATH
    }

    fun fromVercelCallback(uri: Uri): HostedAddonImport? {
      if (uri.encodedFragment != null) return null
      val deploymentUrl = uri.getQueryParameters("deployment-url").singleOrNull() ?: return null
      return fromUrl(deploymentUrl, allowManifest = false)?.takeIf { it.provider == Provider.VERCEL }
    }

    private fun parseUri(url: String): URI? =
      try {
        URI(url)
      } catch (_: URISyntaxException) {
        null
      }

    private fun URI.httpsHost(): String? {
      val host = host ?: return null
      if (!scheme.equals("https", ignoreCase = true) || !rawAuthority.equals(host, ignoreCase = true)) {
        return null
      }
      return host.lowercase(Locale.ROOT)
    }
  }
}

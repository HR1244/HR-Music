package echo.music.iad1tya.echomusic.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

@Suppress("DEPRECATION")
internal fun isInstallableUpdate(context: Context, apk: File, expectedVersion: Long): Boolean {
  if (!apk.isFile) return false
  val manager = context.packageManager
  val signingFlags =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
    else PackageManager.GET_SIGNATURES
  return try {
    val installed = manager.getPackageInfo(context.packageName, signingFlags)
    val candidate = manager.getPackageArchiveInfo(apk.absolutePath, signingFlags) ?: return false
    val candidateVersion = PackageInfoCompat.getLongVersionCode(candidate)
    val installedSigners = currentSigners(installed)
    candidate.packageName == installed.packageName &&
      candidateVersion == expectedVersion &&
      candidateVersion > PackageInfoCompat.getLongVersionCode(installed) &&
      installedSigners.isNotEmpty() &&
      currentSigners(candidate) == installedSigners
  } catch (_: PackageManager.NameNotFoundException) {
    false
  } catch (_: RuntimeException) {
    false
  }
}

@Suppress("DEPRECATION")
private fun currentSigners(info: PackageInfo): Set<Signature> =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
  } else {
    info.signatures?.toSet().orEmpty()
  }

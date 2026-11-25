package echo.music.iad1tya.echomusic.updater.downloadmanager

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.getDownloadedApksDir
import echo.music.iad1tya.echomusic.updater.isInstallableUpdate
import echo.music.iad1tya.echomusic.updater.parseUpdateAsset
import echo.music.iad1tya.echomusic.updater.updateRepository
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class UpdateDownloadWorker(private val context: Context, workerParams: WorkerParameters) :
  CoroutineWorker(context, workerParams) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val repository = updateRepository ?: return@withContext Result.failure()
    val version = inputData.getString("version") ?: return@withContext Result.failure()
    val url = inputData.getString("apk_url") ?: return@withContext Result.failure()
    val asset = parseUpdateAsset(repository, version, url) ?: return@withContext Result.failure()
    val fileSize = inputData.getString("file_size").orEmpty()

    DownloadNotificationManager.initialize(context.applicationContext)
    try {
      val notification = DownloadNotificationManager.getDownloadStartingNotification(version, fileSize)
      setForeground(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ForegroundInfo(
            DownloadNotificationManager.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
          )
        } else {
          ForegroundInfo(DownloadNotificationManager.NOTIFICATION_ID, notification)
        }
      )
    } catch (e: CancellationException) {
      throw e
    } catch (_: IllegalStateException) {
      // The in-app progress remains available when Android disallows a foreground service.
    } catch (_: SecurityException) {
      // The in-app progress remains available without notification permission.
    }

    var partialFile: File? = null
    var connection: HttpURLConnection? = null
    try {
      val directory = getDownloadedApksDir(context)
      if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage unavailable")
      val temporary = File.createTempFile(".hr-update-", ".apk", directory)
      partialFile = temporary
      val download = openGitHubDownload(url)
      connection = download
      val fileLength = download.contentLengthLong
      val notifications = context.getSystemService(NotificationManager::class.java)
      var total = 0L
      var lastProgressAt = 0L
      download.inputStream.use { input ->
        temporary.outputStream().buffered().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
            total += count
            val now = SystemClock.elapsedRealtime()
            if (fileLength > 0 && now - lastProgressAt >= 1000) {
              lastProgressAt = now
              val progress = (total.toDouble() / fileLength).coerceIn(0.0, 1.0)
              setProgress(workDataOf("progress" to progress.toFloat()))
              runCatching {
                notifications.notify(
                  DownloadNotificationManager.NOTIFICATION_ID,
                  DownloadNotificationManager.getDownloadProgressNotification(
                    (progress * 100).toInt(), version
                  ),
                )
              }
            }
          }
        }
      }

      if (fileLength > 0 && total != fileLength) throw IOException("Incomplete APK download")
      if (!isInstallableUpdate(context, temporary, asset.versionCode)) {
        runCatching {
          DownloadNotificationManager.showDownloadFailed(
            version, context.getString(R.string.hr_music_update_invalid)
          )
        }
        return@withContext Result.failure()
      }

      val complete = File(directory, asset.name)
      if (!temporary.renameTo(complete)) throw IOException("Could not save the verified APK")
      runCatching { DownloadNotificationManager.showDownloadComplete(version) }
      Result.success(workDataOf("file_path" to complete.absolutePath))
    } catch (e: CancellationException) {
      throw e
    } catch (_: IOException) {
      if (runAttemptCount < 2) return@withContext Result.retry()
      runCatching {
        DownloadNotificationManager.showDownloadFailed(
          version, context.getString(R.string.download_failed)
        )
      }
      Result.failure()
    } finally {
      connection?.disconnect()
      partialFile?.delete()
    }
  }

  private fun openGitHubDownload(url: String): HttpURLConnection {
    var uri = URI(url)
    repeat(3) {
      val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = false
      }
      try {
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_OK) return connection
        val location = connection.getHeaderField("Location")
        if (status !in 300..399 || location == null) throw IOException("APK download failed")
        uri = try {
          uri.resolve(location)
        } catch (e: IllegalArgumentException) {
          throw IOException("Invalid APK download redirect", e)
        }
        if (
          uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
            (uri.host != "release-assets.githubusercontent.com" &&
              uri.host != "objects.githubusercontent.com")
        ) {
          throw IOException("Unexpected APK download host")
        }
      } catch (e: Exception) {
        connection.disconnect()
        throw e
      }
      connection.disconnect()
    }
    throw IOException("Too many APK download redirects")
  }
}

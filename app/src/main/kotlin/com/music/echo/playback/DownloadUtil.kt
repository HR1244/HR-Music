package echo.music.iad1tya.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.media3.database.DatabaseProvider
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.music.echo.extensions.AudioSourceQuality
import com.music.echo.extensions.ExtensionManager
import com.music.echo.extensions.audioFormat
import com.music.innertube.YouTube
import com.music.innertube.models.IpVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.constants.DownloadOnWifiOnlyKey
import echo.music.iad1tya.constants.DownloadQuality
import echo.music.iad1tya.constants.DownloadQualityKey
import echo.music.iad1tya.constants.DownloadWithMetadataKey
import echo.music.iad1tya.constants.IpVersionKey
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.FormatEntity
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.SongEntity
import echo.music.iad1tya.di.DownloadCache
import echo.music.iad1tya.di.PlayerCache
import echo.music.iad1tya.lyrics.LyricsProviderRegistry
import echo.music.iad1tya.lyrics.LyricsUtils
import echo.music.iad1tya.ui.utils.resize
import echo.music.iad1tya.utils.YTPlayerUtils
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.enumPreference
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.Dns
import okhttp3.OkHttpClient

@Singleton
class DownloadUtil
@Inject
constructor(
  @ApplicationContext context: Context,
  val database: MusicDatabase,
  val databaseProvider: DatabaseProvider,
  @DownloadCache val downloadCache: SimpleCache,
  @PlayerCache val playerCache: SimpleCache,
  private val extensionManager: ExtensionManager,
) {
  private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
  private val downloadQuality by
    enumPreference(
      context,
      DownloadQualityKey,
      DownloadQuality.YOUTUBE
    )
  private val ipVersion by enumPreference(context, IpVersionKey, IpVersion.AUTO)
  private val songUrlCache = ConcurrentHashMap<String, ResolvedStream>()
  private var nextFailureNotificationId = 2000

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

  private val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
    ResolvingDataSource.Factory(
      ChunkingDataSourceFactory(
        // Read already-streamed bytes from playerCache instead of re-downloading them:
        // a song played before being downloaded would otherwise be fetched twice.
        CacheDataSource.Factory()
          .setCache(playerCache)
          .setUpstreamDataSourceFactory(
            OkHttpDataSource.Factory(
              OkHttpClient.Builder()
                .dns(
                  object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                      val addresses = Dns.SYSTEM.lookup(hostname)
                      return when (this@DownloadUtil.ipVersion) {
                        IpVersion.IPV4 ->
                          addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                        IpVersion.IPV6 ->
                          addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                        IpVersion.AUTO -> addresses
                      }
                    }
                  }
                )
                .proxy(YouTube.proxy)
                .proxyAuthenticator { _, response ->
                  YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder().header("Proxy-Authorization", auth).build()
                  } ?: response.request
                }
                .build(),
            )
          )
          .setCacheWriteDataSinkFactory(null)
          .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
      )
    ) { dataSpec ->
      val requestUrl = dataSpec.uri.toString()
      if (
        dataSpec.uri.scheme in setOf("https", "http") &&
          (requestUrl.contains("apple.com") ||
            requestUrl.contains("music.apple") ||
            requestUrl.contains(".m3u8") ||
            requestUrl.contains(".mp4") ||
            dataSpec.key?.endsWith("_canvas") == true)
      ) {
        return@Factory dataSpec
      }

      val cacheKey = dataSpec.key ?: error("No media id")
      val (mediaId, quality) = DownloadSource.fromCacheKey(cacheKey)
      songUrlCache[cacheKey]
        ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
        ?.let {
          return@Factory it.resolve(dataSpec)
        }

      val existingFingerprint = downloadCache.getContentMetadata(cacheKey).get(SOURCE_FINGERPRINT, "").orEmpty()
      val addon =
        if (quality == DownloadQuality.HIGH || quality == DownloadQuality.LOSSLESS) {
          runBlocking(Dispatchers.IO) {
            val song = database.getSongById(mediaId)
            val catalogSong =
              if (!mediaId.startsWith("addon_") && (song == null || song.artists.isEmpty())) {
                YouTube.queue(videoIds = listOf(mediaId)).getOrNull()?.firstOrNull { it.id == mediaId }
              } else {
                null
              }
            try {
              extensionManager.resolveStream(
                title = song?.title ?: catalogSong?.title,
                artist = song?.artists?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }
                  ?: catalogSong?.artists?.joinToString(", ") { it.name },
                duration = (song?.song?.duration?.takeIf { it > 0 } ?: catalogSong?.duration)
                  ?.takeIf { it > 0 }?.times(1000L),
                videoId = mediaId,
                accepts = { stream ->
                  val audio = stream.audioFormat
                  (existingFingerprint.isEmpty() || sourceFingerprint(stream.url) == existingFingerprint) &&
                    if (quality == DownloadQuality.LOSSLESS) {
                      audio.isLossless && verifiesLossless(stream.url, audio.codec)
                    } else {
                      !audio.isLossless && audio.bitrate == 320_000
                    }
                }
              )
            } catch (_: IOException) {
              null
            } catch (_: SerializationException) {
              null
            }
          }
        } else {
          null
        }
      if (addon == null && quality == DownloadQuality.LOSSLESS) {
        throw LosslessDownloadUnavailableException()
      }
      val playbackData =
        if (addon == null) {
          runBlocking(Dispatchers.IO) {
            YTPlayerUtils.playerResponseForPlayback(
              videoId = mediaId,
              audioQuality = when (quality) {
                DownloadQuality.LOW -> AudioQuality.LOW
                DownloadQuality.HIGH -> AudioQuality.HIGH
                else -> AudioQuality.AUTO
              },
              connectivityManager = connectivityManager
            ).getOrThrow()
          }
        } else {
          null
        }
      val stream =
        if (addon != null) ResolvedStream(addon.url, System.currentTimeMillis() + 300_000L, emptyMap(), true)
        else ResolvedStream.from(requireNotNull(playbackData))
      val addonAudio = addon?.audioFormat
      val format = playbackData?.format

      if (quality != DownloadQuality.YOUTUBE) {
        val fingerprint = sourceFingerprint(stream.url)
        if (existingFingerprint.isNotEmpty() && existingFingerprint != fingerprint) {
          throw IOException("The download source changed. Retry the download from the beginning.")
        }
        if (existingFingerprint.isEmpty()) {
          val mutations = ContentMetadataMutations().set(SOURCE_FINGERPRINT, fingerprint)
          downloadCache.applyContentMetadataMutations(cacheKey, mutations)
        }
      }

      database.query {
        upsert(
          FormatEntity(
            id = mediaId,
            itag = format?.itag ?: 0,
            mimeType = addonAudio?.mimeType ?: format?.mimeType?.substringBefore(";") ?: "audio/unknown",
            codecs =
              addonAudio?.codec ?: format?.mimeType?.split("codecs=")?.getOrNull(1)?.removeSurrounding("\"") ?: "unknown",
            bitrate = addonAudio?.bitrate ?: format?.bitrate ?: 0,
            sampleRate = format?.audioSampleRate,
            contentLength = format?.contentLength ?: 0L,
            loudnessDb = playbackData?.audioConfig?.loudnessDb,
            perceptualLoudnessDb = playbackData?.audioConfig?.perceptualLoudnessDb,
            playbackUrl = playbackData?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
          ),
        )

        val now = LocalDateTime.now()
        val existingSongInfo = getSongByIdBlocking(mediaId)
        val existing = existingSongInfo?.song

        val downloadMetadata = runBlocking {
          context.dataStore.data.first()[DownloadWithMetadataKey] ?: true
        }
        if (downloadMetadata) {
          scope.launch(Dispatchers.IO) {
            try {
              val title = existingSongInfo?.title ?: playbackData?.videoDetails?.title ?: ""
              val artist =
                existingSongInfo?.artists?.joinToString { it.name }
                  ?: playbackData?.videoDetails?.author
                  ?: ""
              val albumName = existingSongInfo?.album?.title ?: title
              val duration = existing?.duration ?: playbackData?.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0

              // 2. Fetch Lyrics
              val providers =
                LyricsProviderRegistry.getDefaultProviderOrder().mapNotNull {
                  LyricsProviderRegistry.getProviderByName(it)
                }
              var foundLyrics: String? = null
              var foundProvider: String? = null

              for (provider in providers) {
                if (!provider.isEnabled(context)) continue
                val res = provider.getLyrics(mediaId, title, artist, duration, null).getOrNull()
                if (res != null && res != LyricsEntity.LYRICS_NOT_FOUND && res.isNotBlank()) {
                  foundLyrics = res
                  foundProvider = provider.name
                  if (LyricsUtils.hasSyncedLyrics(res)) {
                    break // Synced lyrics, stop searching
                  }
                }
              }
              if (foundLyrics != null && foundProvider != null) {
                database.query {
                  upsert(LyricsEntity(id = mediaId, lyrics = foundLyrics, provider = foundProvider))
                }
              }
            } catch (e: Exception) {
              e.printStackTrace()
            }
          }
        }

        val updatedSong =
          if (existing != null) {
            existing.copy(
              thumbnailUrl =
                existing.thumbnailUrl
                  ?: playbackData?.videoDetails
                    ?.thumbnail
                    ?.thumbnails
                    ?.lastOrNull()
                    ?.url
                    ?.resize(1200, 1200)
            )
          } else {
            SongEntity(
              id = mediaId,
              title = playbackData?.videoDetails?.title ?: downloads.value[mediaId]?.let { Util.fromUtf8Bytes(it.request.data) } ?: "Unknown",
              duration = playbackData?.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
              thumbnailUrl =
                playbackData?.videoDetails
                  ?.thumbnail
                  ?.thumbnails
                  ?.lastOrNull()
                  ?.url
                  ?.resize(1200, 1200),
              dateDownload = null,
              isDownloaded = false
            )
          }

        upsert(updatedSong)

        updatedSong.thumbnailUrl?.let { url ->
          val request =
            ImageRequest.Builder(context)
              .data(url)
              .memoryCachePolicy(CachePolicy.ENABLED)
              .diskCachePolicy(CachePolicy.ENABLED)
              .build()
          SingletonImageLoader.get(context).enqueue(request)
        }
      }

      songUrlCache[cacheKey] = stream
      stream.resolve(dataSpec)
    }

  val downloadNotificationHelper =
    DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

  @OptIn(DelicateCoroutinesApi::class)
  val downloadManager: DownloadManager =
    DownloadManager(
        context,
        databaseProvider,
        downloadCache,
        dataSourceFactory,
        Executors.newFixedThreadPool(3)
      )
      .apply {
        maxParallelDownloads = 3
        addListener(
          object : DownloadManager.Listener {
            override fun onDownloadChanged(
              downloadManager: DownloadManager,
              download: Download,
              finalException: Exception?,
            ) {
              downloads.update { map ->
                map.toMutableMap().apply { set(download.request.id, download) }
              }

              if (download.state == Download.STATE_FAILED &&
                generateSequence(finalException as Throwable?) { it.cause }
                  .any { it is LosslessDownloadUnavailableException }
              ) {
                val message = context.getString(
                  R.string.download_lossless_unavailable, Util.fromUtf8Bytes(download.request.data)
                )
                if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                  val notification = downloadNotificationHelper.buildDownloadFailedNotification(
                    context, R.drawable.error, null, message
                  )
                  NotificationUtil.setNotification(context, nextFailureNotificationId++, notification)
                } else {
                  Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
              }

              scope.launch {
                when (download.state) {
                  Download.STATE_COMPLETED -> {
                    val timestamp = if (download.updateTimeMs > 0) download.updateTimeMs else System.currentTimeMillis()
                    val downloadDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)
                    val title = runCatching { androidx.media3.common.util.Util.fromUtf8Bytes(download.request.data) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown"
                    database.withTransaction {
                      val rowId =
                        insert(
                          SongEntity(
                            id = download.request.id,
                            title = title,
                            dateDownload = downloadDate,
                            isDownloaded = true
                          )
                        )
                      if (rowId == -1L) {
                        updateDownloadedInfo(download.request.id, true, downloadDate)
                      }
                    }
                  }
                  Download.STATE_FAILED,
                  Download.STATE_REMOVING -> {
                    database.updateDownloadedInfo(download.request.id, false, null)
                  }
                  else -> {}
                }
              }
            }

            override fun onDownloadRemoved(
              downloadManager: DownloadManager,
              download: Download,
            ) {
              songUrlCache.remove(download.request.customCacheKey ?: download.request.id)
              downloads.update { map ->
                map.toMutableMap().apply { remove(download.request.id) }
              }
              scope.launch {
                database.updateDownloadedInfo(download.request.id, false, null)
              }
            }
          }
        )
      }

  init {
    val result = mutableMapOf<String, Download>()
    downloadManager.downloadIndex.getDownloads().use { cursor ->
      while (cursor.moveToNext()) {
        result[cursor.download.request.id] = cursor.download
      }
    }
    downloads.value = result

    scope.launch(Dispatchers.IO) {
      result.values.forEach { download ->
        if (download.state == Download.STATE_COMPLETED) {
          val timestamp = if (download.updateTimeMs > 0) download.updateTimeMs else System.currentTimeMillis()
          val downloadDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)
          val title = runCatching { androidx.media3.common.util.Util.fromUtf8Bytes(download.request.data) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown"
          database.withTransaction {
            val rowId =
              insert(
                SongEntity(
                  id = download.request.id,
                  title = title,
                  dateDownload = downloadDate,
                  isDownloaded = true
                )
              )
            if (rowId == -1L) {
              updateDownloadedInfo(download.request.id, true, downloadDate)
            }
          }
        }
      }
    }

    // Wi-Fi-only downloads: DownloadManager pauses queued downloads whenever the
    // active requirements aren't met, so flipping this pref mid-download stops it
    // on mobile data without losing progress.
    scope.launch {
      context.dataStore.data
        .map { it[DownloadOnWifiOnlyKey] ?: false }
        .distinctUntilChanged()
        .collectLatest { wifiOnly ->
          downloadManager.requirements =
            Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        }
    }
  }

  fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

  internal fun prepareRequest(request: DownloadRequest): DownloadRequest {
    if (request.customCacheKey != request.id || request.uri.toString() != request.id) return request
    val existing = downloads.value[request.id]
    if (existing != null && existing.state !in setOf(Download.STATE_FAILED, Download.STATE_REMOVING)) {
      return existing.request
    }
    val selected = downloadQuality
    if (selected == DownloadQuality.YOUTUBE) return request
    val builder = DownloadRequest.Builder(request.id, request.uri)
      .setMimeType(request.mimeType)
      .setStreamKeys(request.streamKeys)
      .setKeySetId(request.keySetId)
      .setCustomCacheKey(DownloadSource(request.id, selected).newCacheKey())
      .setData(request.data)
    request.byteRange?.let { builder.setByteRange(it.offset, it.length) }
    return builder.build()
  }

  internal fun completedDownloadCacheKey(mediaId: String): String? =
    downloads.value[mediaId]?.takeIf { it.state == Download.STATE_COMPLETED }?.request
      ?.let { it.customCacheKey ?: it.id }

  internal fun completedDownloadQuality(mediaId: String): DownloadQuality? =
    completedDownloadCacheKey(mediaId)?.let { DownloadSource.fromCacheKey(it).quality }

  private fun verifiesLossless(url: String, codec: String?): Boolean {
    if (Uri.parse(url).scheme !in setOf("https", "http")) return false
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(url, emptyMap())
      val audioTracks = (0 until extractor.trackCount)
        .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        .filter { it.startsWith("audio/") }
        .map { AudioSourceQuality.from(it) }
      audioTracks.isNotEmpty() && audioTracks.all { it.isLossless && it.codec == codec }
    } catch (_: Exception) {
      false
    } finally {
      extractor.release()
    }
  }

  private fun sourceFingerprint(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

  fun release() {
    scope.cancel()
  }

  companion object {
    private const val SOURCE_FINGERPRINT = "hrmusic.download.source"
    /**
     * Computes the aggregated download state for a collection of song IDs.
     * - [Download.STATE_COMPLETED] if all songs are completed.
     * - [Download.STATE_DOWNLOADING] if at least one song is currently queued or downloading.
     * - [Download.STATE_STOPPED] otherwise (idle, stopped, or empty).
     */
    fun computeDownloadState(
      songIds: Collection<String>?,
      downloads: Map<String, Download>,
    ): Int {
      if (songIds.isNullOrEmpty()) return Download.STATE_STOPPED
      return if (songIds.all { downloads[it]?.state == Download.STATE_COMPLETED }) {
        Download.STATE_COMPLETED
      } else if (
        songIds.any {
          downloads[it]?.state == Download.STATE_QUEUED ||
            downloads[it]?.state == Download.STATE_DOWNLOADING
        }
      ) {
        Download.STATE_DOWNLOADING
      } else {
        Download.STATE_STOPPED
      }
    }

    /**
     * Returns true if the song is currently completed, queued, or downloading.
     */
    fun hasDownload(songId: String, downloads: Map<String, Download>): Boolean {
      val state = downloads[songId]?.state
      return state == Download.STATE_COMPLETED ||
        state == Download.STATE_QUEUED ||
        state == Download.STATE_DOWNLOADING
    }
  }
}

private class LosslessDownloadUnavailableException : IOException("No verified lossless source is available")

package echo.music.iad1tya.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.music.echo.extensions.AudioSourceQuality
import echo.music.iad1tya.utils.isLocalMediaId

internal class PlaybackBufferControl(
  private val verifyTrack: (String, AudioSourceQuality) -> Unit,
  private val bandwidthEstimate: () -> Long,
  private val isDataRestricted: () -> Boolean,
  private val playbackAllocator: DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
) : DefaultLoadControl(
  playbackAllocator,
  360_000,
  360_000,
  150,
  500,
  C.LENGTH_UNSET,
  true,
  0,
  false,
) {
  private class LosslessPeriod(val bitrate: Int) {
    var filling = true
    var waitingSinceMs: Long? = null
  }

  private val losslessPeriods = LinkedHashMap<MediaPeriodId, LosslessPeriod>()
  private val timelinePeriod = Timeline.Period()
  private val timelineWindow = Timeline.Window()

  override fun onTracksSelected(
    parameters: LoadControl.Parameters,
    trackGroups: TrackGroupArray,
    trackSelections: Array<out ExoTrackSelection?>,
  ) {
    super.onTracksSelected(parameters, trackGroups, trackSelections)
    val index = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
    val format =
      trackSelections.firstOrNull { it?.trackGroup?.type == C.TRACK_TYPE_AUDIO }?.selectedFormat
    if (index == C.INDEX_UNSET || format == null) return
    val windowIndex = parameters.timeline.getPeriod(index, timelinePeriod).windowIndex
    val mediaId = parameters.timeline.getWindow(windowIndex, timelineWindow).mediaItem.mediaId
    val actual = AudioSourceQuality.from(
      format.sampleMimeType, format.codecs, format.averageBitrate, format.sampleRate,
    )
    verifyTrack(mediaId, actual)
    val periodId = parameters.mediaPeriodId
    if (!actual.isLossless || timelineWindow.isLive || mediaId.isLocalMediaId()) {
      losslessPeriods.remove(periodId)
      return
    }
    if (periodId !in losslessPeriods && losslessPeriods.size >= 32) {
      losslessPeriods.remove(losslessPeriods.keys.first())
    }
    val bitrate = actual.bitrate ?: estimatedLosslessBitrate(format.sampleRate, format.channelCount)
    if (losslessPeriods[periodId]?.bitrate != bitrate) losslessPeriods[periodId] = LosslessPeriod(bitrate)
  }

  override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
    val period = losslessPeriods[parameters.mediaPeriodId] ?: return super.shouldContinueLoading(parameters)
    val targetUs = if (isDataRestricted()) 20 * SECOND_US else 60 * SECOND_US
    val mediaTargetUs = targetUs.forPlaybackSpeed(parameters.playbackSpeed)
    when {
      atLosslessBufferLimit() || parameters.bufferedDurationUs >= mediaTargetUs -> period.filling = false
      parameters.bufferedDurationUs < (mediaTargetUs * 2 / 3) -> period.filling = true
    }
    return period.filling
  }

  override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
    val period = losslessPeriods[parameters.mediaPeriodId] ?: return super.shouldStartPlayback(parameters)
    val index = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
    val durationUs =
      if (index == C.INDEX_UNSET) C.TIME_UNSET
      else parameters.timeline.getPeriod(index, timelinePeriod).durationUs
    val desiredUs = losslessRunwayUs(
      bandwidthEstimate(), period.bitrate, parameters.playbackSpeed, parameters.rebuffering,
      isDataRestricted(),
    )
    val targetUs = capToRemainingAudioUs(desiredUs, durationUs, parameters.playbackPositionUs)
    val nowMs = SystemClock.elapsedRealtime()
    val startedMs = period.waitingSinceMs ?: nowMs.also { period.waitingSinceMs = it }
    val ready = atLosslessBufferLimit() || losslessBufferReady(
      parameters.bufferedDurationUs, targetUs, nowMs - startedMs, parameters.playbackSpeed,
      parameters.rebuffering,
    )
    if (ready) period.waitingSinceMs = null
    return ready
  }

  private fun atLosslessBufferLimit(): Boolean =
    playbackAllocator.totalBytesAllocated >= LOSSLESS_MAX_BUFFER_BYTES

  private companion object {
    // Keep large uncompressed streams from using all of a phone's process memory.
    const val LOSSLESS_MAX_BUFFER_BYTES = 64 * 1024 * 1024
  }
}

private const val SECOND_US = 1_000_000L

internal fun estimatedLosslessBitrate(sampleRate: Int, channels: Int): Int {
  // A conservative compressed estimate when the extractor cannot report encoded bitrate.
  val rate = (sampleRate.takeIf { it > 0 } ?: 44_100).coerceAtMost(768_000)
  val channelCount = (channels.takeIf { it > 0 } ?: 2).coerceAtMost(32)
  return rate * channelCount * 14
}

internal fun losslessRunwayUs(
  bandwidthBitsPerSecond: Long,
  trackBitsPerSecond: Int,
  playbackSpeed: Float,
  rebuffering: Boolean,
  isDataRestricted: Boolean,
): Long {
  val ratio = if (bandwidthBitsPerSecond > 0 && trackBitsPerSecond > 0) {
    bandwidthBitsPerSecond.toDouble() / (trackBitsPerSecond * playbackSpeed.toDouble())
  } else 1.2
  val seconds = if (rebuffering) {
    when {
      ratio >= 2.5 -> 3
      ratio >= 1.5 -> 4
      ratio >= 0.95 -> 6
      else -> 9
    }
  } else {
    when {
      ratio >= 2.5 -> 3
      ratio >= 1.5 -> 5
      ratio >= 0.95 -> 9
      else -> 15
    }
  }
  val maxSeconds = if (rebuffering) 7 else 10
  return ((if (isDataRestricted) seconds.coerceAtMost(maxSeconds) else seconds) * SECOND_US)
    .forPlaybackSpeed(playbackSpeed)
}

internal fun capToRemainingAudioUs(targetUs: Long, durationUs: Long, positionUs: Long): Long =
  if (durationUs == C.TIME_UNSET) targetUs
  else (durationUs - positionUs.coerceAtLeast(0)).coerceIn(0, targetUs)

internal fun losslessBufferReady(
  bufferedUs: Long,
  targetUs: Long,
  waitedMs: Long,
  playbackSpeed: Float,
  rebuffering: Boolean,
): Boolean {
  if (bufferedUs >= targetUs) return true
  val waitLimitMs = if (rebuffering) 12_000L else 8_000L
  val minimumUs = ((if (rebuffering) 4 else 3) * SECOND_US).forPlaybackSpeed(playbackSpeed)
  return waitedMs >= waitLimitMs && bufferedUs >= minimumUs
}

private fun Long.forPlaybackSpeed(speed: Float): Long = (this * speed.toDouble()).toLong()

package echo.music.iad1tya.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.music.echo.extensions.AudioSourceQuality

internal class PlaybackBufferControl(
  private val verifyTrack: (String, AudioSourceQuality) -> Unit,
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
  private val losslessPeriods = LinkedHashMap<MediaPeriodId, Boolean>()
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
    val actual = AudioSourceQuality.from(format.sampleMimeType, format.codecs, format.averageBitrate)
    verifyTrack(mediaId, actual)
    if (losslessPeriods.size >= 32) losslessPeriods.remove(losslessPeriods.keys.first())
    losslessPeriods[parameters.mediaPeriodId] = actual.isLossless && !timelineWindow.isLive
  }

  override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
    if (losslessPeriods[parameters.mediaPeriodId] == true) {
      parameters.bufferedDurationUs < LOSSLESS_LOAD_TARGET_US && !atLosslessBufferLimit()
    } else {
      super.shouldContinueLoading(parameters)
    }

  override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
    if (parameters.rebuffering || losslessPeriods[parameters.mediaPeriodId] != true) {
      return super.shouldStartPlayback(parameters)
    }
    val index = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
    val durationUs =
      if (index == C.INDEX_UNSET) C.TIME_UNSET
      else parameters.timeline.getPeriod(index, timelinePeriod).durationUs
    return parameters.bufferedDurationUs >= losslessStartupTargetUs(durationUs, parameters.playbackPositionUs) ||
      atLosslessBufferLimit()
  }

  private fun atLosslessBufferLimit(): Boolean =
    playbackAllocator.totalBytesAllocated >= LOSSLESS_MAX_BUFFER_BYTES

  private companion object {
    const val LOSSLESS_LOAD_TARGET_US = 90_000_000L
    // Keep large uncompressed streams from using all of a phone's process memory.
    const val LOSSLESS_MAX_BUFFER_BYTES = 64 * 1024 * 1024
  }
}

internal fun losslessStartupTargetUs(durationUs: Long, positionUs: Long): Long {
  val minuteUs = 60_000_000L
  if (durationUs == C.TIME_UNSET) return minuteUs
  return (durationUs - positionUs).coerceIn(0, minuteUs)
}

package com.music.echo.extensions

import java.util.Locale

internal data class AudioSourceQuality(
  val mimeType: String,
  val codec: String?,
  val bitrate: Int?,
) {
  val isLossless: Boolean
    get() =
      when {
        codec in LOSSLESS_CODECS -> true
        codec != null -> false
        mimeType in LOSSLESS_MIME_TYPES -> true
        else -> false
      }

  companion object {
    private val LOSSLESS_CODECS = setOf("flac", "alac", "pcm")
    private val LOSSLESS_MIME_TYPES = setOf("audio/flac", "audio/alac", "audio/raw")
    private val CODECS = Regex("""(?:^|;)\s*codecs\s*=\s*"?([^";,]+)""", RegexOption.IGNORE_CASE)
    private val KBPS = Regex("""^(\d+)\s*kbps$""", RegexOption.IGNORE_CASE)

    fun from(format: String?, codec: String? = null, bitrate: Int? = null): AudioSourceQuality {
      val value = format.orEmpty().trim().lowercase(Locale.ROOT)
      val source = value.substringBefore(';').trim()
      val mimeType =
        when (source) {
          "flac", "audio/x-flac" -> "audio/flac"
          "alac", "audio/x-alac" -> "audio/alac"
          "pcm", "audio/pcm" -> "audio/raw"
          "wav", "audio/x-wav", "audio/wave" -> "audio/wav"
          "aac" -> "audio/aac"
          "m4a", "mp4" -> "audio/mp4"
          "mp3" -> "audio/mpeg"
          "webm" -> "audio/webm"
          "opus" -> "audio/opus"
          "ogg" -> "audio/ogg"
          else -> source.ifBlank { "application/octet-stream" }
        }
      val declaredCodec = codec?.trim()?.takeIf(String::isNotEmpty)
        ?: CODECS.find(value)?.groupValues?.get(1)?.trim()
      val normalizedCodec =
        when (val name = declaredCodec?.lowercase(Locale.ROOT)) {
          "fla", "flac" -> "flac"
          "alac", "apple lossless" -> "alac"
          "linear pcm", "pcm", "lpcm", "sowt", "twos" -> "pcm"
          "aac", "mp4a" -> "aac"
          null -> when (mimeType) {
            "audio/flac" -> "flac"
            "audio/alac" -> "alac"
            "audio/raw" -> "pcm"
            "audio/aac", "audio/mp4a-latm" -> "aac"
            "audio/opus" -> "opus"
            "audio/mpeg" -> "mp3"
            else -> null
          }
          else -> if (name.startsWith("mp4a.")) "aac" else name
        }
      val resolvedMimeType =
        when {
          normalizedCodec == "flac" -> "audio/flac"
          normalizedCodec == "alac" -> "audio/alac"
          normalizedCodec == "pcm" -> "audio/raw"
          mimeType == "application/octet-stream" && normalizedCodec == "opus" -> "audio/opus"
          mimeType == "application/octet-stream" && normalizedCodec == "aac" -> "audio/aac"
          else -> mimeType
        }
      return AudioSourceQuality(resolvedMimeType, normalizedCodec, bitrate?.takeIf { it > 0 })
    }

    fun parseBitrate(quality: String): Int? =
      KBPS.matchEntire(quality.trim())?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it in 1..10_000 }?.times(1000)
  }
}

internal val AddonStreamResponse.audioFormat: AudioSourceQuality
  get() = AudioSourceQuality.from(
    format,
    codec,
    bitrate ?: AudioSourceQuality.parseBitrate(streamQuality) ?: AudioSourceQuality.parseBitrate(quality),
  )

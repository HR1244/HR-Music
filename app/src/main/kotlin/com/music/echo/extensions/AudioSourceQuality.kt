package com.music.echo.extensions

import java.util.Locale

internal data class AudioSourceQuality(
  val mimeType: String,
  val codec: String?,
  val bitrate: Int?,
  val sampleRate: Int? = null,
) {
  val is44100HzFlac: Boolean
    get() = codec == "flac" && sampleRate == 44_100

  val isLossless: Boolean
    get() =
      when {
        codec in LOSSLESS_CODECS -> true
        codec != null -> false
        mimeType in LOSSLESS_MIME_TYPES -> true
        else -> false
      }

  companion object {
    const val FLAC_STREAM_INFO_BYTES = 42
    private val LOSSLESS_CODECS = setOf("flac", "alac", "pcm")
    private val LOSSLESS_MIME_TYPES = setOf("audio/flac", "audio/alac", "audio/raw")
    private val CODECS = Regex("""(?:^|;)\s*codecs\s*=\s*"?([^";,]+)""", RegexOption.IGNORE_CASE)
    private val KBPS = Regex("""^(\d+)\s*kbps$""", RegexOption.IGNORE_CASE)

    fun from(
      format: String?,
      codec: String? = null,
      bitrate: Int? = null,
      sampleRate: Int? = null,
    ): AudioSourceQuality {
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
      return AudioSourceQuality(
        resolvedMimeType, normalizedCodec, bitrate?.takeIf { it > 0 }, sampleRate?.takeIf { it > 0 },
      )
    }

    fun nativeFlacSampleRate(bytes: ByteArray): Int? {
      if (bytes.size < FLAC_STREAM_INFO_BYTES ||
        bytes[0] != 'f'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
        bytes[2] != 'a'.code.toByte() || bytes[3] != 'C'.code.toByte() ||
        (bytes[4].toInt() and 0x7f) != 0 || bytes[5] != 0.toByte() ||
        bytes[6] != 0.toByte() || bytes[7] != 34.toByte()
      ) return null

      // The first FLAC metadata block is STREAMINFO. Its sample rate occupies 20 bits.
      val rate = ((bytes[18].toInt() and 0xff) shl 12) or
        ((bytes[19].toInt() and 0xff) shl 4) or ((bytes[20].toInt() and 0xff) ushr 4)
      return rate.takeIf { it > 0 }
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
    verifiedFlacSampleRate,
  )

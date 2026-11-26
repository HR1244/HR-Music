package com.music.echo.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourceQualityTest {
  @Test
  fun `native FLAC header distinguishes 44 point 1 and 48 kHz`() {
    val cd = flacHeader(0x0a, 0xc4, 0x42)
    val at48kHz = flacHeader(0x0b, 0xb8, 0x02)
    val declared = AddonStreamResponse(quality = "LOSSLESS")

    assertEquals(44_100, AudioSourceQuality.nativeFlacSampleRate(cd))
    assertEquals(48_000, AudioSourceQuality.nativeFlacSampleRate(at48kHz))
    assertFalse(declared.audioFormat.is44100HzFlac)
    assertTrue(declared.copy(format = "flac", verifiedFlacSampleRate = 44_100).audioFormat.is44100HzFlac)
    assertFalse(declared.copy(format = "flac", verifiedFlacSampleRate = 48_000).audioFormat.is44100HzFlac)
  }

  @Test
  fun `lossy truncated and malformed headers cannot verify native FLAC`() {
    val opus = ByteArray(42).also { "OggS".encodeToByteArray().copyInto(it) }
    val truncated = flacHeader(0x0a, 0xc4, 0x42).copyOf(41)
    val wrongBlockLength = flacHeader(0x0a, 0xc4, 0x42).also { it[7] = 10 }

    assertNull(AudioSourceQuality.nativeFlacSampleRate(opus))
    assertNull(AudioSourceQuality.nativeFlacSampleRate(truncated))
    assertNull(AudioSourceQuality.nativeFlacSampleRate(wrongBlockLength))
  }

  private fun flacHeader(first: Int, second: Int, third: Int): ByteArray =
    ByteArray(42).apply {
      "fLaC".encodeToByteArray().copyInto(this)
      this[4] = 0x80.toByte()
      this[7] = 34
      this[18] = first.toByte()
      this[19] = second.toByte()
      this[20] = third.toByte()
    }
}

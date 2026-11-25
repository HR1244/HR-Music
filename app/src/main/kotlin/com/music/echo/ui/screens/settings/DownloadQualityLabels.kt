package echo.music.iad1tya.ui.screens.settings

import echo.music.iad1tya.R
import echo.music.iad1tya.constants.DownloadQuality

internal val DownloadQuality.labelRes: Int
  get() = when (this) {
    DownloadQuality.YOUTUBE -> R.string.download_quality_default
    DownloadQuality.LOW -> R.string.download_quality_data_saver
    DownloadQuality.HIGH -> R.string.download_quality_up_to_320
    DownloadQuality.LOSSLESS -> R.string.download_quality_lossless_only
  }

internal val DownloadQuality.descriptionRes: Int
  get() = when (this) {
    DownloadQuality.YOUTUBE -> R.string.download_quality_default_desc
    DownloadQuality.LOW -> R.string.download_quality_data_saver_desc
    DownloadQuality.HIGH -> R.string.download_quality_up_to_320_desc
    DownloadQuality.LOSSLESS -> R.string.download_quality_lossless_only_desc
  }

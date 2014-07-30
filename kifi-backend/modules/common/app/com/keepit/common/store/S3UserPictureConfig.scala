package com.keepit.common.store

object S3UserPictureConfig {
  val ImageSizes = Seq(100, 200)
  val sizes = ImageSizes.map(s => ImageSize(s, s))
  val OriginalImageSize = "original"
  val defaultImage = "https://www.kifi.com/assets/img/ghost.200.png"
  val defaultName = "0"
}

package com.keepit.common.images

import java.awt.image.BufferedImage

import com.google.inject.ImplementedBy

import scala.util.Try

@ImplementedBy(classOf[ImageMagic4javaWrapper])
trait Photoshop {
  def resizeImage(image: BufferedImage, boundingBox: Int): Try[BufferedImage]
}

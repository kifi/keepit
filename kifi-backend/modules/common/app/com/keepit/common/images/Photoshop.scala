package com.keepit.common.images

import java.awt.image.BufferedImage

import com.google.inject.ImplementedBy
import com.keepit.model.ImageFormat

import scala.util.Try

@ImplementedBy(classOf[Image4javaWrapper])
trait Photoshop {
  def checkToolsAvailable(): Unit
  def resizeImage(image: BufferedImage, format: ImageFormat, boundingBox: Int): Try[BufferedImage]
  def resizeImage(image: BufferedImage, format: ImageFormat, width: Int, height: Int): Try[BufferedImage]
}

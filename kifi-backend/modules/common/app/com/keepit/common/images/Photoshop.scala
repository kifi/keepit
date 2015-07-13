package com.keepit.common.images

import java.io.File

import com.google.inject.ImplementedBy
import com.keepit.model.ImageFormat

import scala.util.Try

@ImplementedBy(classOf[Image4javaWrapper])
trait Photoshop {
  def checkToolsAvailable(): Unit
  def imageInfo(image: File): Try[RawImageInfo]
  def resizeImage(image: File, format: ImageFormat, boundingBox: Int): Try[File]
  def resizeImage(image: File, format: ImageFormat, width: Int, height: Int): Try[File]
  def cropImage(image: File, format: ImageFormat, width: Int, height: Int): Try[File]
}

case class RawImageInfo(format: String, width: Int, height: Int)
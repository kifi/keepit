package com.keepit.common.store

import java.awt.image.BufferedImage
import scala.util.Try
import org.imgscalr.Scalr
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.ImageIO

case class ImageSize(width: Int, height: Int)
object ImageUtils {
  def resizeImage(rawImage: BufferedImage, size: ImageSize) = {
    val resized = Try { Scalr.resize(rawImage, Math.max(size.height, size.width)) }
    val os = new ByteArrayOutputStream()
    ImageIO.write(resized.getOrElse(rawImage), "jpeg", os)

    (os.size(), new ByteArrayInputStream(os.toByteArray()))
  }
}

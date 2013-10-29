package com.keepit.common.store

import java.awt.image.BufferedImage
import scala.util.Try
import org.imgscalr.Scalr
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.ImageIO
import play.api.libs.json.Json

case class ImageSize(width: Int, height: Int)
case class ImageCropAttributes(x: Int, y: Int, s: Int, /* original image */ h: Int, /* original image */ w: Int)
object ImageCropAttributes {
  implicit val format = Json.format[ImageCropAttributes]
}
object ImageUtils {
  def resizeImageKeepProportions(rawImage: BufferedImage, size: ImageSize) = {
    val resized = Try { Scalr.resize(rawImage, Math.max(size.height, size.width)) }
    val os = new ByteArrayOutputStream()
    ImageIO.write(resized.getOrElse(rawImage), "jpeg", os)

    (os.size(), new ByteArrayInputStream(os.toByteArray()))
  }
  def cropImage(rawImage: BufferedImage, x: Int, y: Int, boxWidth: Int, boxHeight: Int) =
    Scalr.crop(rawImage, x, y, boxWidth, boxHeight)
  def cropSquareImage(rawImage: BufferedImage, x: Int, y: Int, side: Int) =
    cropImage(rawImage, x, y, side, side)

  def bufferedImageToInputStream(image: BufferedImage) = {
    val os = new ByteArrayOutputStream()
    ImageIO.write(image, "jpeg", os)
    (os.size(), new ByteArrayInputStream(os.toByteArray()))
  }
}

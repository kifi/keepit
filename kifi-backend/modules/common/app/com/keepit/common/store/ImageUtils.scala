package com.keepit.common.store

import java.awt.image.BufferedImage
import scala.util.Try
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.ImageIO
import org.imgscalr._
import play.api.libs.json.Json

case class ImageSize(width: Int, height: Int)

//                        w
//       +----------------------------------+
//       |        :                         |
//       |        : (x, y)                  |
//       |  -  -  +-----------+             |
//    h  |        |           |             |
//       |        |           | s           |
//       |        |           |             |
//       |        +-----------+             |
//       |              s                   |
//       +----------------------------------+
//
case class ImageCropAttributes(w: Int, h: Int, x: Int, y: Int, s: Int)

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
  def resizeImageMakeSquare(rawImage: BufferedImage, size: ImageSize) = {
    val cropped = if (rawImage.getHeight != rawImage.getWidth) {
      val h = rawImage.getHeight
      val w = rawImage.getWidth
      if (h > w) {
        Try(cropSquareImage(rawImage, 0, (h-w)/2, w))
      } else {
        Try(cropSquareImage(rawImage, (w-h)/2, 0, h))
      }
    } else Try(rawImage)
    resizeImageKeepProportions(cropped.getOrElse(rawImage), size)
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

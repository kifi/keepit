package com.keepit.common.store

import java.awt.image.BufferedImage
import com.google.inject.Inject
import com.keepit.common.db.Id
import play.api.mvc.{ PathBindable, QueryStringBindable }

import scala.util.Try
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import javax.imageio.ImageIO
import org.imgscalr._
import play.api.libs.json._
import java.awt.Color
import play.api.libs.functional.syntax._

case class ImageSize(width: Int, height: Int)
object ImageSize {
  implicit val format = (
    (__ \ 'width).format[Int] and
    (__ \ 'height).format[Int]
  )(ImageSize.apply _, unlift(ImageSize.unapply))

  private val xRe = """(\d{1,5})x(\d{1,5})""".r
  private def toX(size: ImageSize): String = s"${size.width}x${size.height}"

  def apply(size: String): ImageSize = {
    val xRe(w, h) = size
    ImageSize(w.toInt, h.toInt)
  }

  def apply(bufferedImage: BufferedImage): ImageSize = ImageSize(bufferedImage.getWidth, bufferedImage.getHeight)

  implicit val queryStringBinder = new QueryStringBindable[ImageSize] {
    private val stringBinder = implicitly[QueryStringBindable[String]]
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ImageSize]] = {
      stringBinder.bind(key, params) map {
        case Right(xRe(w, h)) => Right(ImageSize(w.toInt, h.toInt))
        case _ => Left("Unable to bind an image size")
      }
    }
    override def unbind(key: String, size: ImageSize): String = {
      stringBinder.unbind(key, toX(size))
    }
  }

  implicit val pathBinder = new PathBindable[ImageSize] {
    private val stringBinder = implicitly[PathBindable[String]]
    override def bind(key: String, value: String): Either[String, ImageSize] =
      stringBinder.bind(key, value) match {
        case Right(xRe(w, h)) => Right(ImageSize(w.toInt, h.toInt))
        case _ => Left("Unable to bind an image size")
      }
    override def unbind(key: String, size: ImageSize): String = toX(size)
  }
}

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

class ImageUtils @Inject() () {
  def resizeImageKeepProportions(rawImage: BufferedImage, size: ImageSize) = {
    val resized = Try { Scalr.resize(rawImage, Math.max(size.height, size.width)) }
    val os = new ByteArrayOutputStream()
    ImageIO.write(resized.getOrElse(rawImage), "jpeg", os)
    val bytes = os.toByteArray()
    (bytes.length, new ByteArrayInputStream(bytes))
  }
  def resizeImageMakeSquare(rawImage: BufferedImage, size: ImageSize) = {
    val cropped = if (rawImage.getHeight != rawImage.getWidth) {
      val h = rawImage.getHeight
      val w = rawImage.getWidth
      if (h > w) {
        Try(cropSquareImage(rawImage, 0, (h - w) / 2, w))
      } else {
        Try(cropSquareImage(rawImage, (w - h) / 2, 0, h))
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

  def forceRGB(image: BufferedImage): BufferedImage = {
    // This forces an image to use RGB and to use white as the transparency color if the source image supports it
    // However, this can still fail on different color modes, especially from images explicitly saved as CMYK from
    // Adobe software. The true solution is to use a full featured image processor, like imagemagick.
    val imageRGB = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)
    val g = imageRGB.createGraphics()
    g.setColor(Color.WHITE)
    g.fillRect(0, 0, image.getWidth, image.getHeight)
    g.drawRenderedImage(image, null)
    g.dispose()
    imageRGB
  }
}

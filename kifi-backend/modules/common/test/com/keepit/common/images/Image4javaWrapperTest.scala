package com.keepit.common.images

import java.awt.image.BufferedImage
import java.io.{ ByteArrayOutputStream, File, FileOutputStream }
import javax.imageio.ImageIO

import com.keepit.model.ImageFormat
import com.keepit.test.CommonTestInjector
import org.specs2.execute.{ Failure, FailureException, Result }
import org.specs2.mutable.Specification
import play.api.Mode

class Image4javaWrapperTest extends Specification with CommonTestInjector {

  def getPngImage(name: String = "image1"): File = new File(s"test/data/$name.png")
  def getJpgImage(name: String = "image1"): File = new File(s"test/data/$name.jpg")
  def getGifImage(name: String = "image1"): File = new File(s"test/data/$name.gif")

  def range(actual: Int, expected: Int, window: Double = 0.25): Result = {
    val delta = Math.abs((actual - expected).toDouble / actual.toDouble)
    val msg = s"actual: $actual, expected: $expected, window: $window, delta = $delta"
    if (delta > window) {
      throw new FailureException(Failure(msg, stackTrace = new Exception().getStackTrace.toList.drop(1)))
    } else {
      success(msg)
    }
  }

  def imageByteSize(img: File): Int = {
    img.getAbsoluteFile.length().toInt
  }

  "ImageMagic4javaWrapper" should {

    "checkToolsAvailable" in {
      val im = new Image4javaWrapper(Mode.Test)
      im.checkToolsAvailable() //should not throw an exception
      1 === 1
    }

    "get image info from imagemagick (png)" in {
      val image = getPngImage()
      val im = new Image4javaWrapper(Mode.Test)
      val info = im.imageInfo(image).get
      info.width === 66
      info.height === 38
      1 === 1
    }

    "box resize png" in {
      val image = getPngImage()
      imageByteSize(image) === 612
      val im = new Image4javaWrapper(Mode.Test)

      val resized = im.resizeImage(image, ImageFormat.PNG, 20, 20).get
      val resizedInfo = im.imageInfo(resized).get
      resizedInfo.width === 20
      resizedInfo.height === 12 // == 38 * 20 / 66
      range(imageByteSize(resized), 338)
    }

    "box resize jpg" in {
      val image = getJpgImage()
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 316
      imageInfo.height === 310

      val resized = im.resizeImage(image, ImageFormat.JPG, 100, 100).get
      val resizedInfo = im.imageInfo(resized).get
      resizedInfo.width === 100
      resizedInfo.height === 98

      range(imageByteSize(resized), 1990)
      //      persistImage(resized, "jpg") === 2324
    }

    "box resize gif to png" in {
      val image = getGifImage()
      imageByteSize(image) === 3168406
      val im = new Image4javaWrapper(Mode.Test)

      val resized = im.resizeImage(image, ImageFormat.GIF, 500, 500).get
      val resizedInfo = im.imageInfo(resized).get
      resizedInfo.width === 500
      resizedInfo.height === 282
      range(imageByteSize(resized), 260310, 0.1)
      //      persistImage(resized, "png") === 175173
    }

    "non box resize png" in {
      val image = getPngImage()
      imageByteSize(image) === 612
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 66
      imageInfo.height === 38

      val resized = im.resizeImage(image, ImageFormat.PNG, 60, 30).get
      val resizedInfo = im.imageInfo(resized).get
      resizedInfo.width === 52
      resizedInfo.height === 30
      range(imageByteSize(resized), 863, 0.25)
      //      persistImage(resized, "png") === 606
    }

    "non box resize jpg" in {
      val image = getJpgImage()
      val im = new Image4javaWrapper(Mode.Test)

      val resized = im.resizeImage(image, ImageFormat.JPG, 200, 150).get
      val resizedInfo = im.imageInfo(resized).get
      resizedInfo.width === 153
      resizedInfo.height === 150
      range(imageByteSize(resized), 3281)
      //      persistImage(resized, "jpg") === 3663
    }

    "box crop png" in {
      // the perfect crop of this image is to show only the red Xzibit
      val image = getPngImage("wide_image_3x1")
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 1173
      imageInfo.height === 391

      val cropped = im.centeredCropImage(image, ImageFormat.PNG, 200, 200).get
      val resizedInfo = im.imageInfo(cropped).get
      resizedInfo.width === 200
      resizedInfo.height === 200
      range(imageByteSize(cropped), 48492)
    }

    "box crop jpg" in {
      // the perfect crop of this image is to show only the red Xzibit
      val image = getJpgImage("wide_image_3x1")
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 1173
      imageInfo.height === 391

      val cropped = im.centeredCropImage(image, ImageFormat.JPG, 200, 200).get
      val resizedInfo = im.imageInfo(cropped).get
      resizedInfo.width === 200
      resizedInfo.height === 200
      range(imageByteSize(cropped), 5764)
    }

    "box crop gif" in {
      // the perfect crop of this image is to show only the red Xzibit
      val image = getGifImage("wide_image_3x1")
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 1173
      imageInfo.height === 391

      val cropped = im.centeredCropImage(image, ImageFormat.GIF, 200, 200).get
      val resizedInfo = im.imageInfo(cropped).get
      resizedInfo.width === 200
      range(imageByteSize(cropped), 45071)
    }

    "non box crop png" in {
      // the perfect crop of this image is to show 2 Xzibits
      val image = getPngImage("wide_image_4x1")
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 1564
      imageInfo.height === 391

      val cropped = im.centeredCropImage(image, ImageFormat.PNG, 200, 100).get
      val resizedInfo = im.imageInfo(cropped).get
      resizedInfo.width === 200
      resizedInfo.height === 100
      range(imageByteSize(cropped), 19672)
    }

    "cropscale png" in {
      val image = getPngImage("wide_image_4x1")
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 1564
      imageInfo.height === 391

      val cropscaled = im.cropScaleImage(image, ImageFormat.PNG, x = 1500, y = 300, width = 50, height = 40, finalWidth = 250, finalHeight = 20).get
      val resizedInfo = im.imageInfo(cropscaled).get
      resizedInfo.width === 250
      resizedInfo.height === 20
      range(imageByteSize(cropscaled), 4181)
    }

    "optimize jpg" in {
      val image = getJpgImage("unoptimized1")
      imageByteSize(image) === 79257
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 1200
      imageInfo.height === 720

      val resized = im.resizeImage(image, ImageFormat.JPG, 1200, 1200).get //not really resizing
      val resizedInfo = im.imageInfo(resized).get
      resizedInfo.width === 1200
      resizedInfo.height === 720

      range(imageByteSize(resized), 60611)
    }

    "optimize png" in {
      val image = getPngImage("unoptimized3")
      imageByteSize(image) === 274642
      val im = new Image4javaWrapper(Mode.Test)

      val imageInfo = im.imageInfo(image).get
      imageInfo.width === 500
      imageInfo.height === 333

      val resized = im.resizeImage(image, ImageFormat.PNG, 500, 500).get //not really resizing
      val resizedInfo = im.imageInfo(resized).get
      resizedInfo.width === 500
      resizedInfo.height === 333

      range(imageByteSize(resized), 272072, 0.2)
      //      persistImage(resized, "png") === 205864
    }

  }

}

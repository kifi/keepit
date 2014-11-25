package com.keepit.common.images

import org.specs2.execute.Result
import java.awt.image.BufferedImage
import java.io.{ FileOutputStream, ByteArrayOutputStream, File }
import javax.imageio.ImageIO

import com.keepit.model.ImageFormat
import com.keepit.test.CommonTestInjector
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile

import play.api.Mode
import play.api.Mode._

class Image4javaWrapperTest extends Specification with CommonTestInjector {

  def getPngImage(name: String = "image1"): BufferedImage = ImageIO.read(new File(s"test/data/$name.png"))
  def getJpgImage(name: String = "image1"): BufferedImage = ImageIO.read(new File(s"test/data/$name.jpg"))

  def range(actual: Int, expected: Int, window: Double = 0.04): Result = {
    val delta = Math.abs((actual - expected).toDouble / actual.toDouble)
    val msg = s"actual: $actual, expected: $expected, window: $window, delta = $delta"
    if (delta > window) {
      failure(msg)
    } else {
      success(msg)
    }
  }

  def imageByteSize(img: BufferedImage, format: String): Int = {
    val tmp = new ByteArrayOutputStream()
    ImageIO.write(img, format, tmp)
    tmp.close()
    tmp.size()
  }

  def persistImage(img: BufferedImage, format: String): Int = {
    val file = TemporaryFile(new File("image-" + Math.random() + "." + format)).file
    val stream = new FileOutputStream(file)
    ImageIO.write(img, format, stream)
    stream.close()
    val size = file.length()
    println(s"persisting image to file: ${file.getAbsolutePath} of size $size")
    size.toInt
  }

  "ImageMagic4javaWrapper" should {

    "checkToolsAvailable" in {
      val im = new Image4javaWrapper(Mode.Test)
      im.checkToolsAvailable() //should not throw an exception
      1 === 1
    }

//    "box resize png" in {
//      val image = getPngImage()
//      imageByteSize(image, "png") === 627
//      image.getWidth === 66
//      image.getHeight === 38
//      val im = new Image4javaWrapper(Mode.Test)
//      val resized = im.resizeImage(image, ImageFormat.PNG, 20).get
//      resized.getWidth === 20
//      resized.getHeight === 12 // == 38 * 20 / 66
//      range(imageByteSize(resized, "png"), 270)
//      //      persistImage(resized, "png") === 265
//    }

    "box resize jpg" in {
      val image = getJpgImage()
      imageByteSize(image, "jpg") === 11101
      image.getWidth === 316
      image.getHeight === 310
      val im = new Image4javaWrapper(Mode.Test)
      val resized = im.resizeImage(image, ImageFormat.JPG, 100).get
      resized.getWidth === 100
      resized.getHeight === 98
      range(imageByteSize(resized, "jpg"), 2330)
      //      persistImage(resized, "jpg") === 2324
    }

//    "non box resize png" in {
//      val image = getPngImage()
//      imageByteSize(image, "png") === 627
//      image.getWidth === 66
//      image.getHeight === 38
//      val im = new Image4javaWrapper(Mode.Test)
//      val resized = im.resizeImage(image, ImageFormat.PNG, 60, 30).get
//      resized.getWidth === 52
//      resized.getHeight === 30
//      range(imageByteSize(resized, "png"), 618)
//      //      persistImage(resized, "png") === 606
//    }

    "non box resize jpg" in {
      val image = getJpgImage()
      imageByteSize(image, "jpg") === 11101
      image.getWidth === 316
      image.getHeight === 310
      val im = new Image4javaWrapper(Mode.Test)
      val resized = im.resizeImage(image, ImageFormat.JPG, 200, 150).get
      resized.getWidth === 153
      resized.getHeight === 150
      range(imageByteSize(resized, "jpg"), 3670)
      //      persistImage(resized, "jpg") === 3663
    }

    "optimize jpg" in {
      val image = getJpgImage("unoptimized1")
      imageByteSize(image, "jpg") === 79251
      image.getWidth === 1200
      image.getHeight === 720
      val im = new Image4javaWrapper(Mode.Test)
      val resized = im.resizeImage(image, ImageFormat.JPG, 1200).get //not really resizing
      resized.getWidth === 1200
      resized.getHeight === 720
      range(imageByteSize(resized, "jpg"), 65445)
      //      persistImage(resized, "jpg") === 65439
    }

    "optimize png" in {
      val image = getPngImage("unoptimized3")
      imageByteSize(image, "png") === 465305
      image.getWidth === 500
      image.getHeight === 333
      val im = new Image4javaWrapper(Mode.Test)
      val resized = im.resizeImage(image, ImageFormat.PNG, 500).get //not really resizing
      resized.getWidth === 500
      resized.getHeight === 333
      range(imageByteSize(resized, "png"), 225392, 0.1)
      //      persistImage(resized, "png") === 205864
    }

  }

}

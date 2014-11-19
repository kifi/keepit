package com.keepit.common.images

import java.awt.image.BufferedImage
import java.io.{ FileOutputStream, ByteArrayOutputStream, File }
import javax.imageio.ImageIO

import com.keepit.model.ImageFormat
import com.keepit.test.CommonTestInjector
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile

class Image4javaWrapperTest extends Specification with CommonTestInjector {

  def getPngImage(name: String = "image1"): BufferedImage = ImageIO.read(new File(s"test/data/$name.png"))
  def getJpgImage(name: String = "image1"): BufferedImage = ImageIO.read(new File(s"test/data/$name.jpg"))

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
      val im = new Image4javaWrapper()
      im.checkToolsAvailable() //should not throw an exception
      1 === 1
    }

    "resize png" in {
      val image = getPngImage()
      imageByteSize(image, "png") === 627
      image.getWidth === 66
      image.getHeight === 38
      val im = new Image4javaWrapper()
      val resized = im.resizeImage(image, 20, ImageFormat.PNG).get
      resized.getWidth === 20
      resized.getHeight === 12 // == 38 * 20 / 66
      imageByteSize(resized, "png") === 265
      //      persistImage(resized, "png") === 265
    }

    "resize jpg" in {
      val image = getJpgImage()
      imageByteSize(image, "jpg") === 11101
      image.getWidth === 316
      image.getHeight === 310
      val im = new Image4javaWrapper()
      val resized = im.resizeImage(image, 100, ImageFormat.JPG).get
      resized.getWidth === 100
      resized.getHeight === 98
      imageByteSize(resized, "jpg") === 2324
      //      persistImage(resized, "jpg") === 2324
    }

    "optimize jpg" in {
      val image = getJpgImage("unoptimized1")
      imageByteSize(image, "jpg") === 79251
      image.getWidth === 1200
      image.getHeight === 720
      val im = new Image4javaWrapper()
      val resized = im.resizeImage(image, 1200, ImageFormat.JPG).get //not really resizing
      resized.getWidth === 1200
      resized.getHeight === 720
      imageByteSize(resized, "jpg") === 65439
      //      persistImage(resized, "jpg") === 65439
    }

    "optimize png" in {
      val image = getPngImage("unoptimized3")
      imageByteSize(image, "png") === 465305
      image.getWidth === 500
      image.getHeight === 333
      val im = new Image4javaWrapper()
      val resized = im.resizeImage(image, 500, ImageFormat.PNG).get //not really resizing
      resized.getWidth === 500
      resized.getHeight === 333
      imageByteSize(resized, "png") === 205864
      //      persistImage(resized, "png") === 205864
    }

  }

}

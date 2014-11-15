package com.keepit.common.images

import java.awt.image.BufferedImage
import java.io.{ByteArrayOutputStream, File}
import javax.imageio.ImageIO

import com.keepit.test.CommonTestInjector
import org.specs2.mutable.Specification

class ImageMagic4javaWrapperTest extends Specification with CommonTestInjector {

  def getPngImage(): BufferedImage = ImageIO.read(new File("test/data/image1.png"))
  def getJpgImage(): BufferedImage = ImageIO.read(new File("test/data/image1.jpg"))

  def imageByteSize(img: BufferedImage): Int = {
    val tmp = new ByteArrayOutputStream()
    ImageIO.write(img, "png", tmp)
    tmp.close()
    tmp.size()
  }

  "ImageMagic4javaWrapper" should {

    "resize png" in {
      val image = getPngImage()
      imageByteSize(image) === 627
      image.getWidth === 66
      image.getHeight === 38
      val im = new ImageMagic4javaWrapper()
      val resized = im.resizeImage(image, 20).get
      resized.getWidth === 20
      resized.getHeight === 12 // == 38 * 20 / 66
      imageByteSize(resized) === 360
    }

    "resize jpg" in {
      val image = getJpgImage()
      imageByteSize(image) === 115911
      image.getWidth === 316
      image.getHeight === 310
      val im = new ImageMagic4javaWrapper()
      val resized = im.resizeImage(image, 20).get
      resized.getWidth === 20
      resized.getHeight === 20
      imageByteSize(resized) === 1306
    }

  }

}

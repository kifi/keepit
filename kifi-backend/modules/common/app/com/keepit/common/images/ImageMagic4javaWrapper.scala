package com.keepit.common.images

import java.awt.image.BufferedImage

import org.im4java.core.{ ConvertCmd, IMOperation, Stream2BufferedImage }

import scala.util.Try

/**
 * Image resize
 * Image Cropping to a square
 */
class ImageMagic4javaWrapper extends Photoshop {

  def resizeImage(image: BufferedImage, boundingBox: Int): Try[BufferedImage] = Try {
    val operation = new IMOperation

    operation.addImage()
    operation.resize(boundingBox, boundingBox)

    operation.define("png:color-type=6") // force rgba colour, iOS does not support indexed (http://en.wikipedia.org/wiki/Portable_Network_Graphics#Color_depth)
    operation.addImage("-")

    val convert = new ConvertCmd(false)
    val s2b = new Stream2BufferedImage()
    convert.setOutputConsumer(s2b)

    try {
      convert.run(operation, image)
    } catch {
      case e: Throwable =>
        if (e.getMessage.contains("Cannot run program \"convert\": error=2, No such file or directory")) {
          throw new Exception(
            """ImageMagic not installed?
              |To install on mac run:
              |  $ brew update
              |  $ brew install imagemagick
              |On Ubuntu:
              |  $ sudo apt-get update
              |  $ sudo apt-get install imagemagick
            """.stripMargin, e)
        }
        throw new Exception("Error executing ImageMagic", e)
    }

    val resized = s2b.getImage
    resized
  }
}
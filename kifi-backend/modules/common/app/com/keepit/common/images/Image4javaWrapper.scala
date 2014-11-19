package com.keepit.common.images

import java.awt.image.BufferedImage

import com.google.inject.Singleton
import com.keepit.model.ImageFormat
import org.im4java.core.{ IMOps, ConvertCmd, IMOperation, Stream2BufferedImage }
import org.im4java.process.ArrayListOutputConsumer
import scala.collection.JavaConversions._

import scala.util.Try

/**
 * Image resize
 * Image Cropping to a square
 */
@Singleton
class Image4javaWrapper extends Photoshop {
  checkToolsAvailable() //call on constructor

  private def command() = new ConvertCmd(false)

  def checkToolsAvailable(): Unit = {
    val operation = new IMOperation
    operation.version()
    val convert = command()
    val output = new ArrayListOutputConsumer()
    convert.setOutputConsumer(output)
    catchExceptions(convert.run(operation))
    println("Graphicsmagick version:")
    output.getOutput foreach { line =>
      println(line)
    }
  }

  private def addOptions(format: ImageFormat, operation: IMOperation): IMOps = format match {
    //                -quality 100 -define webp:lossless=true -define webp:method=6
    // optionally use -quality 80  -define webp:auto-filter=true -define webp:method=6
    case ImageFormat.PNG => operation.strip().quality(95d).colors(2048).define("webp:auto-filter=true").define("webp:method=6")
    // -strip -gaussian-blur 0.05 -quality 75% source.jpg result.jpg
    case ImageFormat.JPG => operation.strip().gaussianBlur(0.05).quality(75d)
    case _ => throw new UnsupportedOperationException(s"Can't resize format $format")
  }

  def resizeImage(image: BufferedImage, boundingBox: Int, format: ImageFormat): Try[BufferedImage] = Try {
    if (format == ImageFormat.UNKNOWN) throw new UnsupportedOperationException(s"Can't resize format $format")
    val operation = new IMOperation

    operation.addImage()
    operation.resize(boundingBox, boundingBox)

    addOptions(format, operation)

    operation.addImage("-")

    val convert = command()
    val s2b = new Stream2BufferedImage()
    convert.setOutputConsumer(s2b)

    catchExceptions(convert.run(operation, image))

    val resized = s2b.getImage
    resized
  }

  private def catchExceptions(block: => Unit): Unit = {
    try {
      block
    } catch {
      case e: Throwable =>
        if (e.getMessage.contains("Cannot run program")) {
          throw new Exception(
            """Graphicsmagick not installed?
              |To install on mac run:
              |  $ brew update
              |  $ brew install imagemagick
              |  See http://brewformulas.org/Imagemagick
              |On Ubuntu:
              |  $ sudo apt-get update
              |  $ sudo apt-get install imagemagick
            """.stripMargin, e)
        }
        throw new Exception("Error executing underlying tool", e)
    }
  }

}
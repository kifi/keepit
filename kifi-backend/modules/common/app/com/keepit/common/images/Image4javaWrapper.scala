package com.keepit.common.images

import java.io.{ PrintWriter, ByteArrayOutputStream }
import com.keepit.common.strings.UTF8
import play.api.Mode
import play.api.Mode._

import java.awt.image.BufferedImage

import com.google.inject.{ Inject, Singleton }
import com.keepit.model.ImageFormat
import org.im4java.core._
import org.im4java.process.{ ErrorConsumer, ArrayListOutputConsumer }
import scala.collection.JavaConversions._

import scala.util.Try

/**
 * Image resize
 * Image Cropping to a square
 */
@Singleton
class Image4javaWrapper @Inject() (
    playMode: Mode) extends Photoshop {
  if (playMode == Mode.Prod) {
    checkToolsAvailable() //call on constructor in production to get a fast fail
  }

  private def command() = new ConvertCmd(false)

  def checkToolsAvailable(): Unit = {
    val operation = new IMOperation
    operation.version()
    val convert = command()
    val output = new ArrayListOutputConsumer()
    convert.setOutputConsumer(output)
    catchExceptions(convert, operation)
    println("Image Magic Version:")
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

  def resizeImage(image: BufferedImage, format: ImageFormat, boundingBox: Int): Try[BufferedImage] =
    resizeImage(image: BufferedImage, format: ImageFormat, boundingBox, boundingBox)

  def resizeImage(image: BufferedImage, format: ImageFormat, width: Int, height: Int): Try[BufferedImage] = Try {
    if (format == ImageFormat.UNKNOWN) throw new UnsupportedOperationException(s"Can't resize format $format")
    val operation = new IMOperation

    operation.verbose()
    operation.addImage()
    operation.resize(width, height)

    addOptions(format, operation)

    operation.addImage("-")

    val convert = command()
    val s2b = new Stream2BufferedImage()
    convert.setOutputConsumer(s2b)

    catchExceptions(convert, operation, Some(image))

    s2b.getImage
  }

  private def getScript(convert: ConvertCmd, operation: IMOperation): String = {
    val baos = new ByteArrayOutputStream()
    val writer = new PrintWriter(baos)
    try {
      convert.createScript(writer, operation, new java.util.Properties(System.getProperties))
    } finally {
      writer.close()
      baos.close()
    }
    new String(baos.toByteArray, UTF8)
  }

  private def catchExceptions(convert: ConvertCmd, operation: IMOperation, image: Option[BufferedImage] = None): Unit = {
    if (playMode == Mode.Test) {
      println(getScript(convert, operation))
    }
    try {
      image match {
        case None => convert.run(operation)
        case Some(img) => convert.run(operation, img)
      }
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
        val script = getScript(convert, operation)
        throw new Exception(s"Error executing underlying tool: ${convert.getErrorText.mkString("\n")}\n Generated script is:\n$script", e)
    }
  }

}

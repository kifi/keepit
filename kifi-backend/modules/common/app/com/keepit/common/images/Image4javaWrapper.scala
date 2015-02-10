package com.keepit.common.images

import java.awt.image.BufferedImage
import java.io.{ ByteArrayOutputStream, PrintWriter }
import java.util
import javax.imageio.ImageIO

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.logging.Logging
import com.keepit.common.strings.UTF8
import com.keepit.model.ImageFormat
import org.im4java.core._
import org.im4java.process.ArrayListOutputConsumer
import play.api.Mode
import play.api.Mode._
import play.api.libs.Files.TemporaryFile

import scala.collection.JavaConversions._
import scala.util.Try

/**
 * Image resize
 * Image Cropping to a square
 */
@Singleton
class Image4javaWrapper @Inject() (
    playMode: Mode) extends Photoshop with Logging {
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
    handleExceptions(convert, operation)
    println("Image Magic Version:")
    output.getOutput foreach { line =>
      println(line)
    }
  }

  private def addOptions(format: ImageFormat, operation: IMOperation): IMOps = format match {
    //                -quality 100 -define webp:lossless=true -define webp:method=6
    // optionally use -quality 80  -define webp:auto-filter=true -define webp:method=6
    case ImageFormat.PNG => operation.strip().quality(95d).define("webp:auto-filter=true").define("webp:method=6")
    // -strip -gaussian-blur 0.05 -quality 75% source.jpg result.jpg
    case ImageFormat.JPG => operation.strip().gaussianBlur(0.05).quality(75d)
    case _ => throw new UnsupportedOperationException(s"Can't resize format $format")
  }

  private def imageByteSize(img: BufferedImage, format: ImageFormat): Int = Try {
    val tmp = new ByteArrayOutputStream()
    ImageIO.write(img, format.value, tmp)
    tmp.close()
    tmp.size()
  } getOrElse (-1) //this is only for logging, I don't want it to break production

  def resizeImage(image: BufferedImage, format: ImageFormat, boundingBox: Int): Try[BufferedImage] =
    resizeImage(image: BufferedImage, format: ImageFormat, boundingBox, boundingBox)

  /**
   * using temporary file for resized image reading. for more info see http://www.imagemagick.org/discourse-server/viewtopic.php?t=19621
   */
  def resizeImage(image: BufferedImage, format: ImageFormat, width: Int, height: Int): Try[BufferedImage] = Try {
    if (format == ImageFormat.UNKNOWN) throw new UnsupportedOperationException(s"Can't resize format $format")
    if (format == ImageFormat.GIF) {
      safeResizeImage(gifToPng(image), ImageFormat.PNG, width, height)
    } else {
      safeResizeImage(image, format, width, height)
    }
  }

  def cropImage(image: BufferedImage, format: ImageFormat, width: Int, height: Int): Try[BufferedImage] = Try {
    if (format == ImageFormat.UNKNOWN) throw new UnsupportedOperationException(s"Can't resize format $format")
    if (format == ImageFormat.GIF) {
      safeCropImage(gifToPng(image), ImageFormat.PNG, width, height)
    } else {
      safeCropImage(image, format, width, height)
    }
  }

  def gifToPng(image: BufferedImage): BufferedImage = {
    val inputFile = TemporaryFile(prefix = "ImageMagicGifToPngImageIn", suffix = ".gif").file
    inputFile.deleteOnExit()
    ImageIO.write(image, "gif", inputFile)

    val outputFile = TemporaryFile(prefix = "ImageMagicGifToPngImageOut", suffix = ".png").file
    outputFile.deleteOnExit()

    val operation = new IMOperation
    operation.addImage(inputFile.getAbsolutePath)

    operation.addImage(outputFile.getAbsolutePath)

    val convert = command()

    handleExceptions(convert, operation)

    ImageIO.read(outputFile)
  }

  private def safeResizeImage(image: BufferedImage, format: ImageFormat, width: Int, height: Int): BufferedImage = {
    val operation = new IMOperation

    val outputFile = TemporaryFile(prefix = "ImageMagicResizeImage", suffix = s".${format.value}").file
    val filePath = outputFile.getAbsolutePath
    outputFile.deleteOnExit()

    operation.addImage()
    operation.resize(width, height)

    addOptions(format, operation)
    operation.addImage(filePath)

    val convert = command()

    handleExceptions(convert, operation, Some(image))

    val resized = ImageIO.read(outputFile)
    log.info(s"resize image from ${imageByteSize(image, format)} bytes (${image.getWidth}w/${image.getWidth}h) to ${imageByteSize(resized, format)} bytes (${resized.getWidth}w/${resized.getWidth}h) using file $filePath")
    resized
  }

  private def safeCropImage(image: BufferedImage, format: ImageFormat, width: Int, height: Int): BufferedImage = {
    log.info(s"[safeCropImage] START format=$format cropWidth=$width cropHeight=$height imageWidth=${image.getWidth} imageHeight=${image.getHeight}")
    val operation = new IMOperation

    val outputFile = TemporaryFile(prefix = "ImageMagicCropImage", suffix = s".${format.value}").file
    val filePath = outputFile.getAbsolutePath
    outputFile.deleteOnExit()

    operation.addImage()

    // minimum resize while preserving aspect ratio
    operation.resize(width, height, '^')

    // crop from the center of the image
    operation.gravity("center")
    operation.crop(width, height, 0, 0)

    // resize the canvas to the size of the crop
    operation.p_repage()

    addOptions(format, operation)
    operation.addImage(filePath)

    val convert = command()
    handleExceptions(convert, operation, Some(image))

    val cropped = ImageIO.read(outputFile)
    log.info(s"[safeCropImage] from ${imageByteSize(image, format)} bytes (${image.getWidth}w/${image.getWidth}h) to ${imageByteSize(cropped, format)} bytes (${cropped.getWidth}w/${cropped.getWidth}h) using file $filePath")
    cropped
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

  private val installationInstructions = """Graphicsmagick not installed?
                                           |To install on mac run:
                                           |  $ brew update
                                           |  $ brew install imagemagick
                                           |  See http://brewformulas.org/Imagemagick
                                           |On Ubuntu:
                                           |  $ sudo apt-get update
                                           |  $ sudo apt-get install imagemagick
                                         """.stripMargin

  private def handleExceptions(convert: ConvertCmd, operation: IMOperation, image: Option[BufferedImage] = None): Unit = {
    if (playMode == Mode.Test) {
      println(getScript(convert, operation))
    }
    try {
      image match {
        case None => convert.run(operation)
        case Some(img) => convert.run(operation, img)
      }
    } catch {
      case topLevelException: Throwable => rootException(topLevelException) match {
        case e: CommandException =>
          if (e.getMessage.contains("Cannot run program")) {
            throw new Exception(installationInstructions, e)
          }
          val script = getScript(convert, operation)
          throw new Exception(s"Error executing underlying tool (return code ${e.getReturnCode}): ${e.getErrorText}\n Generated script is:\n$script", e)
        case t: Throwable =>
          if (t.getMessage.contains("Cannot run program")) {
            throw new Exception(installationInstructions, t)
          }
          val script = getScript(convert, operation)
          val errorText = Option(convert.getErrorText).getOrElse(new util.ArrayList[String]())
          throw new Exception(s"Error executing underlying tool: ${errorText.mkString("\n")}\n Generated script is:\n$script", t)
      }
    }
  }

  private def rootException(e: Throwable): Throwable = {
    val cause = e.getCause
    if (cause == null || cause == e) e else rootException(cause)
  }
}

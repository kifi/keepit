package com.keepit.common.images

import java.io.{ File, ByteArrayOutputStream, PrintWriter }
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

  def imageInfo(image: File): Try[RawImageInfo] = {
    Try {
      val info = new Info(image.getAbsolutePath, true)
      RawImageInfo(info.getImageFormat, info.getImageWidth, info.getImageHeight)
    }
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

  /**
   * using temporary file for resized image reading. for more info see http://www.imagemagick.org/discourse-server/viewtopic.php?t=19621
   */
  def resizeImage(image: File, format: ImageFormat, width: Int, height: Int): Try[File] = Try {
    if (format == ImageFormat.UNKNOWN) throw new UnsupportedOperationException(s"Can't resize format $format")
    if (format == ImageFormat.GIF) {
      safeResizeImage(gifToPng(image), ImageFormat.PNG, width, height)
    } else {
      safeResizeImage(image, format, width, height)
    }
  }

  def centeredCropImage(image: File, format: ImageFormat, width: Int, height: Int): Try[File] = Try {
    if (format == ImageFormat.UNKNOWN) throw new UnsupportedOperationException(s"Can't crop format $format")
    if (format == ImageFormat.GIF) {
      safeCropImage(gifToPng(image), ImageFormat.PNG, width, height)
    } else {
      safeCropImage(image, format, width, height)
    }
  }

  def cropScaleImage(image: File, format: ImageFormat, x: Int, y: Int, width: Int, height: Int, finalWidth: Int, finalHeight: Int): Try[File] = Try {
    if (format == ImageFormat.UNKNOWN) throw new UnsupportedOperationException(s"Can't cropscale format $format")
    if (format == ImageFormat.GIF) {
      safeCropScaleImage(gifToPng(image), ImageFormat.PNG, x, y, width, height, finalWidth, finalHeight)
    } else {
      safeCropScaleImage(image, format, x, y, width, height, finalWidth, finalHeight)
    }
  }

  def gifToPng(inputFile: File): File = {

    val outputFile = TemporaryFile(prefix = "ImageMagicGifToPngImageOut", suffix = ".png").file
    outputFile.deleteOnExit()

    val operation = new IMOperation
    operation.addImage(inputFile.getAbsolutePath + "[0]")

    operation.addImage(outputFile.getAbsolutePath)

    val convert = command()

    handleExceptions(convert, operation)
    outputFile
  }

  private def safeResizeImage(image: File, format: ImageFormat, width: Int, height: Int): File = {
    val operation = new IMOperation

    val outputFile = TemporaryFile(prefix = "ImageMagicResizeImage", suffix = s".${format.value}").file
    val filePath = outputFile.getAbsolutePath
    outputFile.deleteOnExit()

    operation.addImage(image.getAbsolutePath)
    operation.resize(width, height)

    addOptions(format, operation)
    operation.addImage(filePath)

    val convert = command()

    handleExceptions(convert, operation)

    log.info(s"resize image from ${image.getAbsolutePath} (${imageByteSize(image)} bytes) to ${width}x$height at $filePath (${imageByteSize(outputFile)} bytes)")
    outputFile
  }

  private def safeCropImage(image: File, format: ImageFormat, width: Int, height: Int): File = {
    log.info(s"[safeCropImage] START format=$format cropWidth=$width cropHeight=$height")
    val operation = new IMOperation

    val outputFile = TemporaryFile(prefix = "ImageMagicCropImage", suffix = s".${format.value}").file
    val filePath = outputFile.getAbsolutePath
    outputFile.deleteOnExit()

    operation.addImage(image.getAbsolutePath)

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
    handleExceptions(convert, operation)

    log.info(s"[safeCropImage] from ${image.getAbsolutePath} (${imageByteSize(image)} bytes) to ${width}x$height at $filePath (${imageByteSize(outputFile)} bytes)")
    outputFile
  }

  private def safeCropScaleImage(image: File, format: ImageFormat, x: Int, y: Int, width: Int, height: Int, finalWidth: Int, finalHeight: Int): File = {
    log.info(s"[safeCropScaleImage] START format=$format crop=${width}x${height}+${x}+${y}, scale=${finalWidth}x${finalHeight}")
    val operation = new IMOperation

    val outputFile = TemporaryFile(prefix = "ImageMagicCropScaleImage", suffix = s".${format.value}").file
    val filePath = outputFile.getAbsolutePath
    outputFile.deleteOnExit()

    operation.addImage(image.getAbsolutePath)

    operation.crop(width, height, x, y)

    operation.resize(finalWidth, finalHeight, '!')

    addOptions(format, operation)
    operation.addImage(filePath)

    val convert = command()
    handleExceptions(convert, operation)

    log.info(s"[safeCropScaleImage] from ${image.getAbsolutePath} (${imageByteSize(image)} bytes) to ${finalWidth}x$finalHeight at $filePath (${imageByteSize(outputFile)} bytes)")
    outputFile
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

  private def handleExceptions(convert: ConvertCmd, operation: IMOperation): Unit = {
    if (playMode == Mode.Test) {
      // If you need the script printed in tests, uncomment this:
      // println(getScript(convert, operation))
    }
    try {
      convert.run(operation)
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

  private def addOptions(format: ImageFormat, operation: IMOperation): IMOps = format match {
    //                -quality 100 -define webp:lossless=true -define webp:method=6
    // optionally use -quality 80  -define webp:auto-filter=true -define webp:method=6
    case ImageFormat.PNG =>
      operation
        .strip()
        .quality(95d)
        .dither("None")
        .define("png:compression-filter=5")
        .define("png:compression-level=9")
        .define("png:compression-filter=5")
        .define("png:compression-strategy=1")
        .define("png:exclude-chunk=all")
        .define("webp:auto-filter=true")
        .define("webp:method=6")
        .colorspace("sRGB")
        .interlace("None")
    // -strip -gaussian-blur 0.05 -quality 75% source.jpg result.jpg
    case ImageFormat.JPG =>
      operation
        .strip()
        .gaussianBlur(0.05)
        .quality(75d)
        .define("jpeg:fancy-upsampling=off")
        .colorspace("sRGB")
        .interlace("None")
    case _ => throw new UnsupportedOperationException(s"Can't resize format $format")
  }

  private def imageByteSize(img: File): Int = Try {
    img.length().toInt
  } getOrElse (-1) //this is only for logging, I don't want it to break production
}

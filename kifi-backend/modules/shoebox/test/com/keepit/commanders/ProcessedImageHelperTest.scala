package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io.File

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ S3ImageConfig, FakeKeepImageStore, ImageSize, KeepImageStore }
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.io.FileUtils
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ProcessedImageHelperTest extends Specification with ShoeboxTestInjector with Logging {

  val logger = log

  def modules = Seq()

  def dummyImage(width: Int, height: Int) = {
    new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
  }

  def fakeFile1 = {
    val tf = TemporaryFile(new File("test/data/image1-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf.file)
    tf
  }
  def fakeFile2 = {
    val tf = TemporaryFile(new File("test/data/image2-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image2.png"), tf.file)
    tf
  }

  "ProcessedImageHelper" should {
    "calculate resize sizes for an image" in {
      withInjector(modules: _*) { implicit injector =>
        val helper = new FakeProcessedImageHelper {
          calcSizesForImage(dummyImage(100, 100)).toSeq.sorted === Seq()
          calcSizesForImage(dummyImage(300, 100)).toSeq.sorted === Seq(150)
          calcSizesForImage(dummyImage(100, 700)).toSeq.sorted === Seq(150, 400)
          calcSizesForImage(dummyImage(300, 300)).toSeq.sorted === Seq(150)
          calcSizesForImage(dummyImage(1001, 1001)).toSeq.sorted === Seq(150, 400, 1000)
          calcSizesForImage(dummyImage(2000, 1500)).toSeq.sorted === Seq(150, 400, 1000, 1500)
          calcSizesForImage(dummyImage(1500, 1400)).toSeq.sorted === Seq(150, 400, 1000)
        }
        helper === helper
      }
    }

    "convert format types in several ways" in {
      withInjector(modules: _*) { implicit injector =>
        val helper = new FakeProcessedImageHelper {
          ImageFormat.JPG !== ImageFormat.PNG

          inputFormatToOutputFormat(ImageFormat.JPG) === ImageFormat.JPG
          inputFormatToOutputFormat(ImageFormat.UNKNOWN) === ImageFormat.PNG

          imageFormatToJavaFormatName(ImageFormat.JPG) === "jpeg"
          imageFormatToJavaFormatName(ImageFormat("gif")) === "png"

          imageFormatToMimeType(ImageFormat.JPG) === "image/jpeg"
          imageFormatToMimeType(ImageFormat.PNG) === "image/png"

          mimeTypeToImageFormat("image/jpeg") === Some(ImageFormat.JPG)
          mimeTypeToImageFormat("image/jpeg;charset=utf-8") === Some(ImageFormat.JPG)
          mimeTypeToImageFormat("image/png") === Some(ImageFormat.PNG)
          mimeTypeToImageFormat("image/bmp") === Some(ImageFormat("bmp"))

          imageFilenameToFormat("jpeg") === imageFilenameToFormat("jpg")
          imageFilenameToFormat("png") === Some(ImageFormat.PNG)

        }
        helper === helper
      }
    }

    "convert image to input stream" in {
      withInjector(modules: _*) { implicit injector =>
        val helper = new FakeProcessedImageHelper {

          {
            val image = dummyImage(200, 300)
            val res = bufferedImageToInputStream(image, ImageFormat.PNG)
            res.isSuccess === true
            res.get._1 !== null
            res.get._1.read === 137 // first byte of PNG image
            res.get._2 must be_>(50) // check if file is greater than 50B
          }
          {
            val image = dummyImage(200, 300)
            val res = bufferedImageToInputStream(image, ImageFormat.JPG)
            res.isSuccess === true
            res.get._1 !== null
            res.get._1.read === 255 // first byte of JPEG image
            res.get._1.read === 216 // second byte of JPEG image
            res.get._2 must be_>(256) // check if file is greater than 256B, which is smaller than any legit jpeg
          }
          {
            val res = bufferedImageToInputStream(null, ImageFormat.JPG)
            res.isSuccess === false
          }

        }
        helper === helper
      }
    }

    "hash files with MD5" in {
      withInjector(modules: _*) { implicit injector =>
        val helper = new FakeProcessedImageHelper {

          val hashed1 = hashImageFile(fakeFile1.file)
          hashed1.isSuccess === true
          hashed1.get.hash === "26dbdc56d54dbc94830f7cfc85031481"

          val hashed2 = hashImageFile(fakeFile2.file)
          hashed2.isSuccess === true
          hashed2.get.hash === "1b3d95541538044c2a26598fbe1d06ae"

        }
        helper === helper
      }
    }

    "fetch images by URL" in {
      withInjector(modules: _*) { implicit injector =>
        val helper = new FakeProcessedImageHelper {
          val respF = fetchRemoteImage("http://www.doesntmatter.com/")
          val resp = Await.result(respF, Duration("10 seconds"))
          resp._1 === ImageFormat.PNG
          resp._2.file.getName.endsWith(".png") === true
        }
        helper === helper
      }
    }

  }

  "KeepImageSize" should {
    "have several image sizes" in {
      KeepImageSize.allSizes.length must be_>(3)
    }

    "pick the closest KeepImageSize to a given ImageSize" in {
      KeepImageSize(ImageSize(0, 0)) === KeepImageSize.Small
      KeepImageSize(ImageSize(1000, 100)) === KeepImageSize.Medium
      KeepImageSize(ImageSize(900, 900)) === KeepImageSize.Large
    }

    "pick the best KeepImage for a target size" in {
      def genKeepImage(width: Int, height: Int) = {
        KeepImage(keepId = Id[Keep](0), imagePath = "", format = ImageFormat.PNG, width = width, height = height, source = KeepImageSource.UserPicked, sourceFileHash = ImageHash("000"), sourceImageUrl = None, isOriginal = false)
      }
      val keepImages = for {
        width <- 10 to 140 by 11
        height <- 10 to 150 by 17
      } yield genKeepImage(width * 9, height * 9)

      KeepImageSize.pickBest(ImageSize(201, 399), keepImages).get.imageSize === ImageSize(189, 396)
      KeepImageSize.pickBest(ImageSize(800, 840), keepImages).get.imageSize === ImageSize(783, 855)

    }
  }

}

package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io.{ File, FileInputStream }

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.codec.binary.Base64
import org.apache.commons.io.IOUtils
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ProcessedImageHelperTest extends Specification with ShoeboxTestInjector with Logging {

  private val logger = log

  def modules = Seq()

  private def dummyImage(width: Int, height: Int) = {
    new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
  }

  private lazy val base64 = new Base64()
  private lazy val tinyGif = base64.decode("R0lGODlhAQABAIAAAP///wAAACwAAAAAAQABAAACAkQBADs=")
  private lazy val tinyPng = base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVQYV2P4DwABAQEAWk1v8QAAAABJRU5ErkJggg==")
  private lazy val tinyJpg = base64.decode("/9j/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wgALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAAAAD/9oACAEBAAAAAT//2Q==")

  private def testFile(name: String): File = new File("test/data/" + name)

  private def readFile(file: File): Array[Byte] = IOUtils.toByteArray(new FileInputStream(file))

  def scaleRequest(ints: Int*) = ints map { s => ScaleImageRequest.apply(s) }
  def cropRequest(strs: String*) = strs map { str =>
    val Array(w, h) = str.split('x').map(_.toInt)
    CropImageRequest(ImageSize(w, h))
  }

  "ProcessedImageHelper" should {

    "calculate resize sizes for an image" in {
      withInjector(modules: _*) { implicit injector =>
        new FakeProcessedImageHelper {
          def calcSizes(w: Int, h: Int) = calcSizesForImage(ImageSize(w, h), ScaledImageSize.allSizes, CroppedImageSize.allSizes)
          calcSizes(100, 100).toSeq.sorted === Seq()
          calcSizes(300, 100).toSeq.sorted === scaleRequest(150) // no crop, image not wide enough
          calcSizes(100, 700).toSeq.sorted === scaleRequest(150, 400) // no crop, image not wide enough
          calcSizes(300, 300).toSeq.sorted === scaleRequest(150) // no crop, same aspect ratio
          calcSizes(300, 310).toSeq.sorted === scaleRequest(150) ++ cropRequest("150x150")
          calcSizes(1001, 1001).toSeq.sorted === scaleRequest(150, 400, 1000) // scales should take care of crops (same aspect ratio)
          calcSizes(2000, 1500).toSeq.sorted === scaleRequest(150, 400, 1000, 1500) ++ cropRequest("150x150")
          calcSizes(1500, 1400).toSeq.sorted === scaleRequest(150, 400, 1000) ++ cropRequest("150x150")
        }
        1 === 1
      }
    }

    "convert format types in several ways" in {
      withInjector(modules: _*) { implicit injector =>
        new FakeProcessedImageHelper {
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
        1 === 1
      }
    }

    "convert image to input stream" in {
      withInjector(modules: _*) { implicit injector =>
        new FakeProcessedImageHelper {
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
        1 === 1
      }
    }

    "hash files with MD5" in {
      withInjector(modules: _*) { implicit injector =>
        new FakeProcessedImageHelper {
          val hashed1 = hashImageFile(testFile("image1.png"))
          hashed1.isSuccess === true
          hashed1.get.hash === "26dbdc56d54dbc94830f7cfc85031481"

          val hashed2 = hashImageFile(testFile("image2.png"))
          hashed2.isSuccess === true
          hashed2.get.hash === "1b3d95541538044c2a26598fbe1d06ae"
        }
        1 === 1
      }
    }

    "fetch images by URL" in {
      withInjector(modules: _*) { implicit injector =>
        new FakeProcessedImageHelper {

          // detect image format from Content-Type header
          {
            respondWith(Some("image/png"), tinyPng)
            val respF = fetchRemoteImage("http://www.example.com/foo?bar=baz")
            val (format, _) = Await.result(respF, Duration("1s"))
            format === ImageFormat.PNG
          }
          {
            respondWith(Some("image/jpeg"), tinyJpg)
            val respF = fetchRemoteImage("http://www.example.com/")
            val (format, _) = Await.result(respF, Duration("1s"))
            format === ImageFormat.JPG
          }

          // detect image format from file extension in URL
          {
            respondWith(None, tinyPng)
            val respF = fetchRemoteImage("http://www.example.com/foo.png?crop=1xw:0.932295719844358xh;*,*&resize=2300:*&output-format=jpeg&output-quality=90")
            val (format, _) = Await.result(respF, Duration("1s"))
            format === ImageFormat.PNG
          }
          {
            respondWith(None, tinyJpg)
            val respF = fetchRemoteImage("http://www.example.com/foo.jpg")
            val (format, _) = Await.result(respF, Duration("1s"))
            format === ImageFormat.JPG
          }

          // detect image format from file content
          {
            respondWith(None, tinyPng)
            val respF = fetchRemoteImage("http://www.example.com/foo?bar=baz")
            val (format, _) = Await.result(respF, Duration("1s"))
            format === ImageFormat.PNG
          }

          // handle invalid images
          {
            respondWith(None, new Array[Byte](0))
            val respF = fetchRemoteImage("http://www.example.com/foo")
            Await.result(respF, Duration("1s"))
          } must throwA[Exception].like {
            case e =>
              e.getMessage must startWith("Unknown image type, None")
          }
        }
        1 === 1
      }
    }

  }

  "KeepImageSize" should {
    "have several image sizes" in {
      ProcessedImageSize.allSizes.length must be_>(3)
    }

    "pick the closest KeepImageSize to a given ImageSize" in {
      ProcessedImageSize(ImageSize(0, 0)) === ProcessedImageSize.Small
      ProcessedImageSize(ImageSize(1000, 100)) === ProcessedImageSize.Medium
      ProcessedImageSize(ImageSize(900, 900)) === ProcessedImageSize.Large
    }

    "pick the best KeepImage for a target size" in {
      def genKeepImage(width: Int, height: Int) = {
        KeepImage(keepId = Id[Keep](0), imagePath = ImagePath(""), format = ImageFormat.PNG, width = width, height = height, source = ImageSource.UserPicked, sourceFileHash = ImageHash("000"), sourceImageUrl = None, isOriginal = false, kind = ProcessImageOperation.Scale)
      }
      val keepImages = for {
        width <- 10 to 140 by 11
        height <- 10 to 150 by 17
      } yield genKeepImage(width * 9, height * 9)

      ProcessedImageSize.pickBestImage(ImageSize(201, 399), keepImages, false).get.imageSize === ImageSize(189, 396)
      ProcessedImageSize.pickBestImage(ImageSize(800, 840), keepImages, false).get.imageSize === ImageSize(783, 855)
    }
  }

}

package com.keepit.commanders

import java.awt.image.BufferedImage
import java.io.{ File, FileOutputStream, BufferedOutputStream }

import com.google.inject.Injector
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ FakeMailModule, ElectronicMail }
import com.keepit.common.store.{ KeepImageStore, FakeKeepImageStore, ImageSize, FakeShoeboxStoreModule }
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.{ JsError, JsSuccess, Json }

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Success

class KeepImageCommanderTest extends Specification with ShoeboxTestInjector with Logging {

  val logger = log

  def modules = Seq(
    FakeScrapeSchedulerModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule()
  )

  def dummyImage(width: Int, height: Int) = {
    new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
  }

  lazy val fakeFile1 = TemporaryFile(new File("test/data/image1.png"))
  lazy val fakeFile2 = TemporaryFile(new File("test/data/image2.png"))
  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val user = userRepo.save(User(firstName = "Shamdrew", lastName = "Bronner"))
      val lib = libraryRepo.save(Library(name = "Lib1", ownerId = user.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER, showInSearch = true))
      val uri = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val url = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))

      val keep1 = keepRepo.save(Keep(title = Some("G1"), userId = user.id.get, url = url.url, urlId = url.id.get,
        uriId = uri.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE,
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib.id.get)))

      val keep2 = keepRepo.save(Keep(title = Some("G2"), userId = user.id.get, url = url.url, urlId = url.id.get,
        uriId = uri.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE,
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib.id.get)))
      (user, lib, uri, keep1, keep2)
    }
  }

  "KeepImageHelper" should {
    "calculate resize sizes for an image" in {
      withDb(modules: _*) { implicit injector =>
        val helper = new KeepImageHelper {
          val log = logger

          calcSizesForImage(dummyImage(100, 100)).toSeq.sorted === Seq()
          calcSizesForImage(dummyImage(300, 100)).toSeq.sorted === Seq(150)
          calcSizesForImage(dummyImage(100, 700)).toSeq.sorted === Seq(150, 400)
          calcSizesForImage(dummyImage(300, 300)).toSeq.sorted === Seq(150)
          calcSizesForImage(dummyImage(1001, 1001)).toSeq.sorted === Seq(150, 400)
          calcSizesForImage(dummyImage(1999, 1999)).toSeq.sorted === Seq(150, 400, 1000)
        }
        helper === helper
      }
    }

    "convert format types in several ways" in {
      withDb(modules: _*) { implicit injector =>
        val helper = new KeepImageHelper {
          val log = logger

          ImageFormat.JPG !== ImageFormat.PNG

          inputFormatToOutputFormat(ImageFormat.JPG) === ImageFormat.JPG
          inputFormatToOutputFormat(ImageFormat.UNKNOWN) === ImageFormat.PNG

          imageFormatToJavaFormatName(ImageFormat.JPG) === "jpeg"
          imageFormatToJavaFormatName(ImageFormat("gif")) === "png"

          imageFormatToMimeType(ImageFormat.JPG) === "image/jpeg"
          imageFormatToMimeType(ImageFormat.PNG) === "image/png"

          mimeTypeToImageFormat("image/jpeg") === Some(ImageFormat.JPG)
          mimeTypeToImageFormat("image/png") === Some(ImageFormat.PNG)
          mimeTypeToImageFormat("image/bmp") === Some(ImageFormat("bmp"))

          imageFilenameToFormat("jpeg") === imageFilenameToFormat("jpg")
          imageFilenameToFormat("png") === Some(ImageFormat.PNG)

        }
        helper === helper
      }
    }

    "convert image to input stream" in {
      withDb(modules: _*) { implicit injector =>
        val helper = new KeepImageHelper {
          val log = logger;

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
          1 === 1

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

  "KeepImageCommander" should {
    "create varying sizes of images" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[KeepImageCommander]
        val (user, lib, uri, keep1, keep2) = setup()

        {
          val savedF = commander.setKeepImage(fakeFile1, keep1.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("5 seconds"))
          saved === ImageProcessState.StoreSuccess
        }
        {
          val savedF = commander.setKeepImage(fakeFile1, keep2.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("5 seconds"))
          saved === ImageProcessState.StoreSuccess
        }
        // If this complains about not having an `all`, then it's not using FakeKeepImageStore
        inject[KeepImageStore].asInstanceOf[FakeKeepImageStore].all.keySet.size === 1

        val keepImage1 = commander.getBestImageForKeep(keep1.id.get, ImageSize(200, 200))
        keepImage1.nonEmpty === true
        keepImage1.get.sourceImageUrl === None
        keepImage1.get.isOriginal === true
        keepImage1.get.state === KeepImageStates.ACTIVE
        keepImage1.get.format === ImageFormat.PNG
        keepImage1.get.imageSize === ImageSize(66, 38)

        val keepImage2 = commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100))
        keepImage1.get.id !== keepImage2.get.id
        keepImage1.get.sourceFileHash === keepImage2.get.sourceFileHash

        {
          val savedF = commander.setKeepImage(fakeFile2, keep2.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("5 seconds"))
          saved === ImageProcessState.StoreSuccess
        }

        inject[KeepImageStore].asInstanceOf[FakeKeepImageStore].all.keySet.size === 4

        val keepImage3 = commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100))
        keepImage2.get.id !== keepImage3.get.id
        keepImage1.get.sourceFileHash !== keepImage3.get.sourceFileHash

        {
          val savedF = commander.setKeepImage(fakeFile1, keep2.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("5 seconds"))
          saved === ImageProcessState.StoreSuccess
        }

        val keepImage4 = commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100))
        keepImage2.get.id === keepImage4.get.id

        commander.removeKeepImageForKeep(keep2.id.get)
        commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100)) === None

        db.readOnlyMaster { implicit session =>
          // Dependant on what sizes we do
          val all = keepImageRepo.all()
          all.length === 5
          keepImageRepo.getForKeepId(keep1.id.get).length === 1
          keepImageRepo.getForKeepId(keep2.id.get).length === 0
          keepImageRepo.getBySourceHash(keepImage4.get.sourceFileHash).length === 2
        }

        //commander.

        true === true
      }
    }
  }
}

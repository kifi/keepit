package com.keepit.commanders

import java.io.File
import java.security.SecureRandom

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ FakeWebService, WebService }
import com.keepit.common.store._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model._
import com.keepit.test.{ FakeWebServiceModule, ShoeboxTestInjector }
import org.apache.commons.io.FileUtils
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Enumerator
import play.api.libs.ws.WSResponseHeaders
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class KeepImageCommanderTest extends Specification with ShoeboxTestInjector with Logging {

  val logger = log

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeShoeboxStoreModule(),
    FakeWebServiceModule()
  )

  private val random = new SecureRandom()

  def genNewFakeFile1 = {
    val tf = File.createTempFile("tst1", ".png")
    tf.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf)
    tf
  }
  def genNewFakeFile2 = {
    val tf = File.createTempFile("tst2", ".png")
    tf.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image2.png"), tf)
    tf
  }
  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val user = UserFactory.user().withName("Shamdrew", "Bronner").withUsername("test").saved
      val lib = library().saved
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
      val uri = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))

      val keep1 = KeepFactory.keep().withUri(uri).withUser(user).withLibrary(lib).saved
      val keep2 = KeepFactory.keep().withUri(uri).withUser(user).withLibrary(lib).saved
      (user, lib, uri, keep1, keep2)
    }
  }

  "KeepImageCommander" should {
    "create varying sizes of images" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[KeepImageCommander]
        val (user, lib, uri, keep1, keep2) = setup()

        {
          val file = genNewFakeFile1
          val savedF = commander.setKeepImageFromFile(file, keep1.id.get, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
          // if this test fails, make sure imagemagick is installed. Use `brew install imagemagick`
          file.delete()
        }
        {
          val file = genNewFakeFile1
          val savedF = commander.setKeepImageFromFile(file, keep2.id.get, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
          file.delete()
        }

        inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl].all.keySet.size === 1
        val (keepImage1, keepImage2) = db.readOnlyMaster { implicit s =>
          val keepImage1 = commander.getBestImageForKeep(keep1.id.get, ScaleImageRequest(200, 200)).flatten
          keepImage1.nonEmpty === true
          keepImage1.get.sourceImageUrl === None
          keepImage1.get.isOriginal === true
          keepImage1.get.state === KeepImageStates.ACTIVE
          keepImage1.get.format === ImageFormat.PNG
          keepImage1.get.imageSize === ImageSize(66, 38)

          val keepImage2 = commander.getBestImageForKeep(keep2.id.get, ScaleImageRequest(100, 100)).flatten
          keepImage1.get.id !== keepImage2.get.id
          keepImage1.get.sourceFileHash === keepImage2.get.sourceFileHash

          val bulk1 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ScaleImageRequest(200, 200))
          bulk1.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage2.get.id))

          val bulk2 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ScaleImageRequest(100, 100))
          bulk2.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage2.get.id))
          (keepImage1, keepImage2)
        }

        {
          val file = genNewFakeFile2
          val savedF = commander.setKeepImageFromFile(file, keep2.id.get, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(400, 482), 73259)
          file.delete()
        }

        val keys = inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl].all.keySet.map(_.path)
        keys.size === 5
        keys.exists(_.contains("150x150_c")) must beTrue

        // Dependant on image1.png — if changed, this needs to change too.
        inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl].all.find(_._1.path == "keep/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png").nonEmpty === true

        val keepImage4 = db.readOnlyMaster { implicit s =>

          val keepImage3 = commander.getBestImageForKeep(keep2.id.get, ScaleImageRequest(100, 100)).flatten
          keepImage2.get.id !== keepImage3.get.id
          keepImage1.get.sourceFileHash !== keepImage3.get.sourceFileHash

          val bulk3 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ScaleImageRequest(100, 100))
          bulk3.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage3.get.id))

          {
            val file = genNewFakeFile1
            val savedF = commander.setKeepImageFromFile(file, keep2.id.get, ImageSource.UserUpload)
            val saved = Await.result(savedF, Duration("10 seconds"))
            saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
            file.delete()
          }

          val keepImage4 = commander.getBestImageForKeep(keep2.id.get, ScaleImageRequest(100, 100)).flatten
          keepImage2.get.id === keepImage4.get.id

          val bulk4 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ScaleImageRequest(100, 100))
          bulk4.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage2.get.id))

          commander.removeKeepImageForKeep(keep2.id.get)
          commander.getBestImageForKeep(keep2.id.get, ScaleImageRequest(100, 100)) === Some(None)

          val bulk5 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ScaleImageRequest(100, 100))
          bulk5.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> None)

          keepImage4
        }

        db.readOnlyMaster { implicit session =>
          // Dependant on what sizes we do
          val all = keepImageRepo.all()
          all.length === 6
          keepImageRepo.getForKeepId(keep1.id.get).length === 1
          keepImageRepo.getForKeepId(keep2.id.get).length === 0
          keepImageRepo.getBySourceHash(keepImage4.get.sourceFileHash).length === 1
        }

        true === true
      }
    }

    "de-dupe known (by hash) images" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[KeepImageCommander]
        val (user, lib, uri, keep1, _) = setup()

        {
          val file = genNewFakeFile1
          val savedF = commander.setKeepImageFromFile(file, keep1.id.get, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
          file.delete()
        }

        {
          val path = db.readOnlyMaster { implicit session =>
            keepImageRepo.all().head.imagePath
          }
          val existingUrl = inject[S3ImageConfig].cdnBase + "/" + path.path
          val savedF = commander.setKeepImageFromUrl(existingUrl, keep1.id.get, ImageSource.UserPicked)
          val saved = Await.result(savedF, Duration("10 seconds"))

          // If this didn't de-dupe, it would fail, because the HTTP fetcher is disabled when no application is running
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 0)
        }

        true === true
      }
    }

    "patiently create missing image sizes" in {
      withDb(modules: _*) { implicit injector =>
        val kiRepo = inject[KeepImageRepo]
        val file = genNewFakeFile2
        val file2 = genNewFakeFile2

        val ws = inject[WebService].asInstanceOf[FakeWebService]
        ws.setGlobalStreamResponse { url =>
          val headers = new WSResponseHeaders {
            override def status: Int = 200
            override def headers: Map[String, Seq[String]] = Map("Content-Type" -> Seq("image/png"))
          }
          val content: Array[Byte] = FileUtils.readFileToByteArray(file2)
          (headers, Enumerator(content))
        }

        val commander = inject[KeepImageCommander]
        val (user, lib, uri, keep1, _) = setup()
        val keepId = keep1.id.get

        {
          val savedF = commander.setKeepImageFromFile(file, keepId, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(400, 482), 73259)

          db.readWrite { implicit rw =>
            val images = kiRepo.getForKeepId(keepId)
            images.size === 4

            val croppedKeepImage = images.find(_.kind == ProcessImageOperation.CenteredCrop).get
            // pretend like this keep image was never created
            kiRepo.save(croppedKeepImage.copy(state = KeepImageStates.INACTIVE, keepId = Id[Keep](424242)))
          }
        }

        {
          val keepImagesF = commander.getBestImagesForKeepsPatiently(Set(keepId), CenteredCropImageRequest(150, 150))
          val keepImages = Await.result(keepImagesF, Duration("10 seconds"))

          val keepImage = keepImages(keepId).get
          keepImage.width === 150
          keepImage.height === 150
          keepImage.kind === ProcessImageOperation.CenteredCrop
        }

        // 2 scaled, 1 cropped, 1 original
        db.readOnlyMaster { implicit s => kiRepo.getForKeepId(keepId).size === 4 }

        // let's make sure this still works for the original image versions
        {
          val keepImagesF = commander.getBestImagesForKeepsPatiently(Set(keepId), ScaleImageRequest(300, 300))
          val keepImage = Await.result(keepImagesF, Duration("10 seconds"))(keepId).get
          keepImage.width === 332
          keepImage.height === 400
          keepImage.kind === ProcessImageOperation.Scale
        }
        {
          val keepImagesF = commander.getBestImagesForKeepsPatiently(Set(keepId), ScaleImageRequest(500, 500))
          val keepImages = Await.result(keepImagesF, Duration("10 seconds"))
          keepImages(keepId).get.isOriginal must beTrue
        }

        file.delete()
        file2.delete()
      }
    }
  }

}

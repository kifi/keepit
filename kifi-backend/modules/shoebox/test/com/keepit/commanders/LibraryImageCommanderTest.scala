package com.keepit.commanders

import java.io.File
import com.keepit.model.LibraryFactoryHelper._
import com.google.inject.Injector
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ InMemoryRoverImageStoreImpl, RoverImageStore, ImageSize }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactory._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.io.FileUtils
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import com.keepit.model.UserFactoryHelper._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibraryImageCommanderTest extends Specification with ShoeboxTestInjector with Logging {

  val logger = log

  def modules = Seq()

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
      val user = UserFactory.user().withName("Noraa", "Ush").withUsername("test").saved
      val lib = library().saved
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER))

      (user, lib)
    }
  }

  "LibraryImageCommander" should {

    "upload image only" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[LibraryImageCommander]
        val libraryImageRepo = inject[LibraryImageRepo]
        val (user, lib) = setup()

        // upload an image
        {
          val file = genNewFakeFile1
          val position = LibraryImagePosition(Some(40), None)
          val savedF = commander.uploadLibraryImageFromFile(file, lib.id.get, position, ImageSource.UserUpload, user.id.get)(HeimdalContext.empty)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
          // if this test fails, make sure imagemagick is installed. Use `brew install imagemagick`
          file.delete()
        }

        inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl].all.keySet.size === 1

        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getActiveForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(40), None))
        }

        // upload same image
        {
          val file = genNewFakeFile1
          val position = LibraryImagePosition(Some(0), Some(100))
          val savedF = commander.uploadLibraryImageFromFile(file, lib.id.get, position, ImageSource.UserUpload, user.id.get)(HeimdalContext.empty)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
          file.delete()
        }
        inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl].all.keySet.size === 1
        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getActiveForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(0), Some(100)))
        }

        // upload another image
        {
          val file = genNewFakeFile2
          val position = LibraryImagePosition(None, Some(77))
          val savedF = commander.uploadLibraryImageFromFile(file, lib.id.get, position, ImageSource.UserUpload, user.id.get)(HeimdalContext.empty)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(400, 482), 73259)
          file.delete()
        }

        inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl].all.keySet.size === 4

        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getActiveForLibraryId(lib.id.get)
          libImages.length === 3
          libImages.map(_.position === LibraryImagePosition(None, Some(77)))
          libraryImageRepo.getAllForLibraryId(lib.id.get).length === 4
        }

      }
    }

    "upload image, position, then remove" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[LibraryImageCommander]
        val libraryImageRepo = inject[LibraryImageRepo]
        val (user, lib) = setup()

        // upload may not specify a position
        val position = LibraryImagePosition(None, None)
        val file = genNewFakeFile1
        val savedF = commander.uploadLibraryImageFromFile(file, lib.id.get, position, ImageSource.UserUpload, user.id.get)(HeimdalContext.empty)
        val saved = Await.result(savedF, Duration("10 seconds"))
        saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)

        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getActiveForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(None, None))
        }

        // position one dimension
        commander.positionLibraryImage(lib.id.get, LibraryImagePosition(Some(30), None))
        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getActiveForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(30), None))
        }

        // position both dimensions
        commander.positionLibraryImage(lib.id.get, LibraryImagePosition(Some(35), Some(45)))
        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getActiveForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(35), Some(45)))
        }

        // remove image
        commander.removeImageForLibrary(lib.id.get, user.id.get)(HeimdalContext.empty) === true
        db.readOnlyMaster { implicit s =>
          libraryImageRepo.getActiveForLibraryId(lib.id.get).length === 0
          libraryImageRepo.getAllForLibraryId(lib.id.get).length === 1
        }
      }
    }

  }
}

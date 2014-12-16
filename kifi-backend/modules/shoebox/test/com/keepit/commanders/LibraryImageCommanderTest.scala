package com.keepit.commanders

import java.io.File
import com.keepit.model.LibraryFactoryHelper._
import com.google.inject.Injector
import com.keepit.common.logging.Logging
import com.keepit.common.store.{ LibraryImageStore, FakeLibraryImageStore }
import com.keepit.model.LibraryFactory._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.io.FileUtils
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibraryImageCommanderTest extends Specification with ShoeboxTestInjector with Logging {

  val logger = log

  def modules = Seq()

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
  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val user = userRepo.save(User(firstName = "Noraa", lastName = "Ush", username = Username("test"), normalizedUsername = "test"))
      val lib = library().saved
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER, showInSearch = true, visibility = LibraryMembershipVisibilityStates.VISIBLE))

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
          val position = LibraryImagePosition(Some(40), None)
          val savedF = commander.uploadLibraryImageFromFile(fakeFile1, lib.id.get, position, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess // if this test fails, make sure imagemagick is installed. Use `brew install imagemagick`
        }
        // If this complains about not having an `all`, then it's not using FakeKeepImageStore
        inject[LibraryImageStore].asInstanceOf[FakeLibraryImageStore].all.keySet.size === 1

        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(40), None))
        }

        // upload same image
        {
          val position = LibraryImagePosition(Some(0), Some(100))
          val savedF = commander.uploadLibraryImageFromFile(fakeFile1, lib.id.get, position, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess
        }
        inject[LibraryImageStore].asInstanceOf[FakeLibraryImageStore].all.keySet.size === 1
        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(0), Some(100)))
        }

        // upload another image
        {
          val position = LibraryImagePosition(None, Some(77))
          val savedF = commander.uploadLibraryImageFromFile(fakeFile2, lib.id.get, position, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess
        }
        inject[LibraryImageStore].asInstanceOf[FakeLibraryImageStore].all.keySet.size === 4

        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libImages.length === 3
          libImages.map(_.position === LibraryImagePosition(None, Some(77)))
          libraryImageRepo.getForLibraryId(lib.id.get, None).length === 4
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
        val savedF = commander.uploadLibraryImageFromFile(fakeFile1, lib.id.get, position, ImageSource.UserUpload)
        val saved = Await.result(savedF, Duration("10 seconds"))
        saved === ImageProcessState.StoreSuccess

        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(None, None))
        }

        // position one dimension
        commander.positionLibraryImage(lib.id.get, LibraryImagePosition(Some(30), None))
        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(30), None))
        }

        // position both dimensions
        commander.positionLibraryImage(lib.id.get, LibraryImagePosition(Some(35), Some(45)))
        db.readOnlyMaster { implicit s =>
          val libImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libImages.length === 1
          libImages.map(_.position === LibraryImagePosition(Some(35), Some(45)))
        }

        // remove image
        commander.removeImageForLibrary(lib.id.get) === true
        db.readOnlyMaster { implicit s =>
          libraryImageRepo.getForLibraryId(lib.id.get).length === 0
          libraryImageRepo.getForLibraryId(lib.id.get, None).length === 1
        }
      }
    }

  }
}

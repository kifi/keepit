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
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER, showInSearch = true))

      (user, lib)
    }
  }

  "LibraryImageCommander" should {

    "upload image only" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[LibraryImageCommander]
        val libraryImageRepo = inject[LibraryImageRepo]
        val (user, lib) = setup()

        // upload 2 images
        {
          val savedF = commander.uploadLibraryImageFromFile(fakeFile1, lib.id.get, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved.isInstanceOf[ImageProcessState.StoreSuccess] === true // if this test fails, make sure imagemagick is installed. Use `brew install imagemagick`
        }
        // If this complains about not having an `all`, then it's not using FakeKeepImageStore
        inject[LibraryImageStore].asInstanceOf[FakeLibraryImageStore].all.keySet.size === 1

        {
          val savedF = commander.uploadLibraryImageFromFile(fakeFile2, lib.id.get, ImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved.isInstanceOf[ImageProcessState.StoreSuccess] === true
        }
        inject[LibraryImageStore].asInstanceOf[FakeLibraryImageStore].all.keySet.size === 4

        db.readOnlyMaster { implicit s =>
          libraryImageRepo.getForLibraryId(lib.id.get).length === 0 // no uploaded image should be active
          val libImages = libraryImageRepo.getForLibraryId(lib.id.get, None)
          libImages.length === 4
          libImages.map { img =>
            img.imagePosition === LibraryImagePosition(None, None) // all images should have null positions
          }
        }

      }
    }

    "upload image, position, then remove" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[LibraryImageCommander]
        val libraryImageRepo = inject[LibraryImageRepo]
        val (user, lib) = setup()

        val savedF = commander.uploadLibraryImageFromFile(fakeFile1, lib.id.get, ImageSource.UserUpload)
        val saved = Await.result(savedF, Duration("10 seconds"))
        saved.isInstanceOf[ImageProcessState.StoreSuccess] === true

        val hash = db.readOnlyMaster { implicit s =>
          val libraryImages = libraryImageRepo.getForLibraryId(lib.id.get, None)
          libraryImages.map(_.imagePosition === LibraryImagePosition(None, None))
          libraryImages.map(_.sourceFileHash).toSet.head
        }

        // re-position one dimension (should default)
        commander.positionLibraryImage(lib.id.get, hash, LibraryImagePosition(Some(30), None))
        db.readOnlyMaster { implicit s =>
          val libraryImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libraryImages.length === 1
          libraryImages.map { libImage =>
            libImage.imagePosition === LibraryImagePosition(Some(30), Some(50))
          }
        }

        // position both dimensions
        commander.positionLibraryImage(lib.id.get, hash, LibraryImagePosition(Some(35), Some(45)))
        db.readOnlyMaster { implicit s =>
          libraryImageRepo.getForLibraryId(lib.id.get).map { libImage =>
            libImage.imagePosition === LibraryImagePosition(Some(35), Some(45))
          }
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

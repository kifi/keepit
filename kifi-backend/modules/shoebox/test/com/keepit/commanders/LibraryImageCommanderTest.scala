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
    val (width, height) = (66, 38)
    (tf, width, height)
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

    "upload & remove library image" in {
      withDb(modules: _*) { implicit injector =>

        val commander = inject[LibraryImageCommander]

        val libraryImageRepo = inject[LibraryImageRepo]
        val (user, lib) = setup()
        val (file1, width, height) = fakeFile1
        val sizing = LibraryImageSelection(0, 0, 100, 100)
        val savedF = commander.setLibraryImageFromFile(file1, lib.id.get, sizing, BaseImageSource.UserUpload)
        val saved = Await.result(savedF, Duration("10 seconds"))
        saved === BaseImageProcessState.StoreSuccess // if this test fails, make sure imagemagick is installed. Use `brew install imagemagick`

        // If this complains about not having an `all`, then it's not using FakeKeepImageStore
        inject[LibraryImageStore].asInstanceOf[FakeLibraryImageStore].all.keySet.size === 1

        db.readOnlyMaster { implicit s =>
          val libraryImages = libraryImageRepo.getForLibraryId(lib.id.get)
          libraryImages.length === 1
          libraryImageRepo.getBySourceHash(libraryImages(0).sourceFileHash).length === 1
        }

        commander.removeImageForLibrary(lib.id.get) === true

        db.readOnlyMaster { implicit s =>
          libraryImageRepo.getForLibraryId(lib.id.get).length === 0
          libraryImageRepo.getForLibraryId(lib.id.get, None).length === 1
        }
      }
    }

    "upload image then adjust position" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[LibraryImageCommander]

        val libraryImageRepo = inject[LibraryImageRepo]
        val (user, lib) = setup()
        val (file1, width, height) = fakeFile1

        val sizing = LibraryImageSelection(0, 0, 100, 100)
        val savedF = commander.setLibraryImageFromFile(file1, lib.id.get, sizing, BaseImageSource.UserUpload)
        val saved = Await.result(savedF, Duration("10 seconds"))
        saved === BaseImageProcessState.StoreSuccess

        db.readOnlyMaster { implicit s =>
          libraryImageRepo.getForLibraryId(lib.id.get).map { libImage =>
            libImage.imageSelection === LibraryImageSelection(0, 0, 100, 100)
          }
        }

        val newSizing = LibraryImageSelection(1, 1, 100, 100)
        commander.setLibraryImageSizing(lib.id.get, newSizing)

        db.readOnlyMaster { implicit s =>
          libraryImageRepo.getForLibraryId(lib.id.get).map { libImage =>
            libImage.imageSelection === LibraryImageSelection(1, 1, 100, 100)
          }
        }
      }
    }

  }
}

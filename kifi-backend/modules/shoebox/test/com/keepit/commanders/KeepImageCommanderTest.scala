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

class KeepImageCommanderTest extends Specification with ShoeboxTestInjector with Logging {

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
      val user = userRepo.save(User(firstName = "Shamdrew", lastName = "Bronner", username = Username("test"), normalizedUsername = "test"))
      val lib = libraryRepo.save(Library(name = "Lib1", ownerId = user.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user.id.get, access = LibraryAccess.OWNER, showInSearch = true))
      val uri = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val url = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))

      val keep1 = keepRepo.save(Keep(title = Some("G1"), userId = user.id.get, url = url.url, urlId = url.id.get,
        uriId = uri.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE,
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib.id.get), inDisjointLib = lib.isDisjoint))

      val keep2 = keepRepo.save(Keep(title = Some("G2"), userId = user.id.get, url = url.url, urlId = url.id.get,
        uriId = uri.id.get, source = KeepSource.keeper, state = KeepStates.ACTIVE,
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib.id.get), inDisjointLib = lib.isDisjoint))
      (user, lib, uri, keep1, keep2)
    }
  }

  "KeepImageCommander" should {
    "create varying sizes of images" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[KeepImageCommander]
        val (user, lib, uri, keep1, keep2) = setup()

        {
          val savedF = commander.setKeepImageFromFile(fakeFile1, keep1.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess
        }
        {
          val savedF = commander.setKeepImageFromFile(fakeFile1, keep2.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess
        }
        // If this complains about not having an `all`, then it's not using FakeKeepImageStore
        inject[KeepImageStore].asInstanceOf[FakeKeepImageStore].all.keySet.size === 1

        val keepImage1 = commander.getBestImageForKeep(keep1.id.get, ImageSize(200, 200)).flatten
        keepImage1.nonEmpty === true
        keepImage1.get.sourceImageUrl === None
        keepImage1.get.isOriginal === true
        keepImage1.get.state === KeepImageStates.ACTIVE
        keepImage1.get.format === ImageFormat.PNG
        keepImage1.get.imageSize === ImageSize(66, 38)

        val keepImage2 = commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100)).flatten
        keepImage1.get.id !== keepImage2.get.id
        keepImage1.get.sourceFileHash === keepImage2.get.sourceFileHash

        val bulk1 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ImageSize(200, 200))
        bulk1.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage2.get.id))

        val bulk2 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ImageSize(100, 100))
        bulk2.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage2.get.id))

        {
          val savedF = commander.setKeepImageFromFile(fakeFile2, keep2.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess
        }

        inject[KeepImageStore].asInstanceOf[FakeKeepImageStore].all.keySet.size === 4
        // Dependant on image1.png — if changed, this needs to change too.
        inject[KeepImageStore].asInstanceOf[FakeKeepImageStore].all.find(_._1 == "keep/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png").nonEmpty === true

        val keepImage3 = commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100)).flatten
        keepImage2.get.id !== keepImage3.get.id
        keepImage1.get.sourceFileHash !== keepImage3.get.sourceFileHash

        val bulk3 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ImageSize(100, 100))
        bulk3.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage3.get.id))

        {
          val savedF = commander.setKeepImageFromFile(fakeFile1, keep2.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess
        }

        val keepImage4 = commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100)).flatten
        keepImage2.get.id === keepImage4.get.id

        val bulk4 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ImageSize(100, 100))
        bulk4.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> Some(keepImage2.get.id))

        commander.removeKeepImageForKeep(keep2.id.get)
        commander.getBestImageForKeep(keep2.id.get, ImageSize(100, 100)) === Some(None)

        val bulk5 = commander.getBestImagesForKeeps(Set(keep1.id.get, keep2.id.get), ImageSize(100, 100))
        bulk5.mapValues(_.map(_.id)) === Map(keep1.id.get -> Some(keepImage1.get.id), keep2.id.get -> None)

        db.readOnlyMaster { implicit session =>
          // Dependant on what sizes we do
          val all = keepImageRepo.all()
          all.length === 5
          keepImageRepo.getForKeepId(keep1.id.get).length === 1
          keepImageRepo.getForKeepId(keep2.id.get).length === 0
          keepImageRepo.getBySourceHash(keepImage4.get.sourceFileHash).length === 2
        }

        true === true
      }
    }

    "de-dupe known (by hash) images" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[KeepImageCommander]
        val (user, lib, uri, keep1, _) = setup()

        {
          val savedF = commander.setKeepImageFromFile(fakeFile1, keep1.id.get, KeepImageSource.UserUpload)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess
        }

        {
          val path = db.readOnlyMaster { implicit session =>
            keepImageRepo.all().head.imagePath
          }
          val existingUrl = inject[S3ImageConfig].cdnBase + "/" + path
          val savedF = commander.setKeepImageFromUrl(existingUrl, keep1.id.get, KeepImageSource.UserPicked)
          val saved = Await.result(savedF, Duration("10 seconds"))

          // If this didn't de-dupe, it would fail, because the HTTP fetcher is disabled when no application is running
          saved === ImageProcessState.StoreSuccess
        }

        true === true
      }
    }
  }

}

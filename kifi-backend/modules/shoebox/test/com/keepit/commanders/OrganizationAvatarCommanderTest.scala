package com.keepit.commanders

import java.io.File

import com.google.inject.Injector
import com.keepit.common.logging.Logging
import com.keepit.common.store._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.io.FileUtils
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import com.keepit.commanders.OrganizationAvatarConfiguration._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class OrganizationAvatarCommanderTest extends Specification with ShoeboxTestInjector with Logging {

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
      val user1 = UserFactory.user().withName("Abe", "Lincoln").withUsername("abe").saved
      val user2 = UserFactory.user().withName("Bob", "Dole").withUsername("bob").saved
      val org1 = inject[OrganizationRepo].save(Organization(name = "Abe's Hardware", ownerId = user1.id.get, primaryHandle = None, description = None, site = None))
      val org2 = inject[OrganizationRepo].save(Organization(name = "Bob's Tools", ownerId = user2.id.get, primaryHandle = None, description = None, site = None))
      inject[OrganizationMembershipRepo].save(org1.newMembership(userId = user1.id.get, role = OrganizationRole.ADMIN))
      inject[OrganizationMembershipRepo].save(org2.newMembership(userId = user2.id.get, role = OrganizationRole.ADMIN))
      (user1, user2, org1, org2)
    }
  }

  "organization avatar commander" should {
    "upload new avatar, and all processed derivatives" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[OrganizationAvatarCommander]
        val store = inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl]
        val repo = inject[OrganizationAvatarRepo]
        val (_, _, org1, _) = setup()

        // upload an image
        {
          val savedF = commander.persistOrganizationAvatarsFromUserUpload(org1.id.get, fakeFile1, cropRegion = SquareImageCropRegion(ImageOffset(0, 0), 50))
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved must haveClass[Right[ImageStoreFailure, ImageHash]]
          saved.right.get === ImageHash("26dbdc56d54dbc94830f7cfc85031481")
          // if this test fails, make sure imagemagick is installed. Use `brew install imagemagick`
        }

        store.all.keySet.size === OrganizationAvatarConfiguration.numSizes

        db.readOnlyMaster { implicit s =>
          val org1Avatars = repo.getByOrgId(org1.id.get)
          org1Avatars.length === OrganizationAvatarConfiguration.numSizes
        }
      }
    }
    "deactivate old avatars when a new one is uploaded" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[OrganizationAvatarCommander]
        val store = inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl]
        val repo = inject[OrganizationAvatarRepo]
        val (_, _, org1, _) = setup()

        {
          val savedF = commander.persistOrganizationAvatarsFromUserUpload(org1.id.get, fakeFile1, cropRegion = SquareImageCropRegion(ImageOffset(0, 0), 50))
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved must haveClass[Right[ImageStoreFailure, ImageHash]]
          saved.right.get === ImageHash("26dbdc56d54dbc94830f7cfc85031481")
        }

        store.all.keySet.size === OrganizationAvatarConfiguration.numSizes

        db.readOnlyMaster { implicit s =>
          repo.getByOrgId(org1.id.get).length === OrganizationAvatarConfiguration.numSizes
        }

        {
          val savedF = commander.persistOrganizationAvatarsFromUserUpload(org1.id.get, fakeFile2, cropRegion = SquareImageCropRegion(ImageOffset(0, 0), 50))
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved must haveClass[Right[ImageStoreFailure, ImageHash]]
          saved.right.get === ImageHash("1b3d95541538044c2a26598fbe1d06ae")
          // if this test fails, make sure imagemagick is installed. Use `brew install imagemagick`
        }

        // All the images have been uploaded and persisted
        store.all.keySet.size === 2 * OrganizationAvatarConfiguration.numSizes

        // Only the second avatars are active
        db.readOnlyMaster { implicit s =>
          repo.getByOrgId(org1.id.get).length === OrganizationAvatarConfiguration.numSizes
          repo.count === 2 * OrganizationAvatarConfiguration.numSizes
        }

      }
    }
    "be sure that every distinct upload gets its own file path" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[OrganizationAvatarCommander]
        val store = inject[RoverImageStore].asInstanceOf[InMemoryRoverImageStoreImpl]
        val repo = inject[OrganizationAvatarRepo]
        val (_, _, org1, _) = setup()

        val n = 20
        for (x <- 1 to n) {
          val savedF = commander.persistOrganizationAvatarsFromUserUpload(org1.id.get, fakeFile1, cropRegion = SquareImageCropRegion(ImageOffset(x, x), 50))
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved must haveClass[Right[ImageStoreFailure, ImageHash]]
          saved.right.get === ImageHash("26dbdc56d54dbc94830f7cfc85031481")
        }

        store.all.keySet.size === n * OrganizationAvatarConfiguration.numSizes

        db.readOnlyMaster { implicit s =>
          repo.count === n * OrganizationAvatarConfiguration.numSizes
          repo.all.map(_.imagePath).toSet.size === n * OrganizationAvatarConfiguration.numSizes
          // Now check the actually active avatars
          repo.getByOrgId(org1.id.get).length === OrganizationAvatarConfiguration.numSizes
        }
      }
    }
  }
}

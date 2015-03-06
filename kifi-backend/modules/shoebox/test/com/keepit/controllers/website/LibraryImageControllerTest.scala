package com.keepit.controllers.website

import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import java.io.File

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId, FakeCryptoModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import org.apache.commons.io.FileUtils
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class LibraryImageControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  val controllerTestModules = Seq(
    FakeCryptoModule(),
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeScraperServiceClientModule(),
    FakeKeepImportsModule(),
    FakeSliderHistoryTrackerModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeHttpClientModule()
  )

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val user1 = userRepo.save(User(firstName = "Noraa", lastName = "Ush", username = Username("test"), normalizedUsername = "test"))
      val lib1 = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("l"), memberCount = 1))
      membership().withLibraryOwner(lib1).saved
      val lib2 = libraryRepo.save(Library(name = "L2", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("l2"), memberCount = 1))
      membership().withLibraryOwner(lib2).saved

      // user2 has READ_ONLY membership to user1's library
      val user2 = userRepo.save(User(firstName = "Noraa", lastName = "Ush", username = Username("test2"), normalizedUsername = "test2"))
      membership().withLibraryFollower(lib1, user2).saved
      (user1, lib1, lib2, user2)
    }
  }

  "LibraryImageController" should {

    "support image uploads" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1, lib2, user2) = setup()
        implicit val config = inject[PublicIdConfiguration]
        val libPubId1 = Library.publicId(lib1.id.get)
        val libPubId2 = Library.publicId(lib2.id.get)

        db.readOnlyMaster { implicit s =>
          keepImageRepo.count === 0
          keepImageRequestRepo.count === 0
          libraryImageRepo.count === 0
          libraryImageRequestRepo.count === 0
        } //making sure db repo is initiated
        // Invalid Access to Library (wrong user)
        status(uploadFile(user2, libPubId1, fakeFile, None, None, None)) === FORBIDDEN

        // Correct Upload, no position specified
        val uploadResp1 = uploadFile(user1, libPubId1, fakeFile, None, None, None)
        status(uploadResp1) === OK
        Json.parse(contentAsString(uploadResp1)) === Json.parse(
          """{"path": "library/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png", "x": 50, "y": 50}""")

        // Upload again to same library - same image path, one position dimension specified
        val uploadResp2 = uploadFile(user1, libPubId1, fakeFile, None, Some(20), None)
        status(uploadResp2) === OK
        Json.parse(contentAsString(uploadResp2)) === Json.parse(
          """{"path" : "library/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png", "x": 20, "y": 50}""")

        // Upload again to different library - same image path, a different position dimension specified
        val uploadResp3 = uploadFile(user1, libPubId2, fakeFile, None, None, Some(100))
        status(uploadResp3) === OK
        Json.parse(contentAsString(uploadResp3)) === Json.parse(
          """{"path" : "library/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png", "x": 50, "y": 100}""")
      }
    }

    "apply positioning to library images" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1, _, user2) = setup()
        implicit val config = inject[PublicIdConfiguration]
        val libPubId1 = Library.publicId(lib1.id.get)

        // Correct Upload
        val uploadResp = uploadFile(user1, libPubId1, fakeFile, None, None, None)
        status(uploadResp) === OK
        val imagePath1 = (contentAsJson(uploadResp) \ "path").as[String]

        // apply positioning to bad imageUrl
        status(positionImage(user1, libPubId1, Json.obj("path" -> "keep/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png", "x" -> 20))) === BAD_REQUEST // wrong path
        status(positionImage(user1, libPubId1, Json.obj("path" -> "library/asdf_30x20.jpg", "x" -> 20))) === BAD_REQUEST // bad image hash

        // Invalid Access to Library (wrong user)
        status(positionImage(user2, libPubId1, Json.obj("path" -> imagePath1, "x" -> 5))) === FORBIDDEN

        // apply positioning with one dimension specified
        val position1 = Json.obj("path" -> imagePath1, "x" -> 20)
        status(positionImage(user1, libPubId1, position1)) === NO_CONTENT

        // apply positioning with both dimensions specified
        val position2 = Json.obj("path" -> imagePath1, "x" -> 21, "y" -> 51)
        status(positionImage(user1, libPubId1, position2)) === NO_CONTENT
      }
    }

    "remove library image" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1, _, user2) = setup()
        implicit val config = inject[PublicIdConfiguration]
        val libPubId1 = Library.publicId(lib1.id.get)

        // Correct Upload
        val uploadResp = uploadFile(user1, libPubId1, fakeFile, None, None, None)
        status(uploadResp) === OK

        // Invalid Access to Library (wrong user)
        status(removeImage(user2, libPubId1)) === FORBIDDEN

        // remove image
        val result1 = removeImage(user1, libPubId1)
        status(result1) === NO_CONTENT

        // remove image which doesn't exist
        val result2 = removeImage(user1, libPubId1)
        status(result2) === NO_CONTENT
      }
    }

  }

  private def uploadFile(user: User, libraryId: PublicId[Library], file: TemporaryFile, imageSize: Option[String], posX: Option[Int], posY: Option[Int])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    inject[LibraryImageController].uploadLibraryImage(libraryId, imageSize, posX, posY)(request(routes.LibraryImageController.uploadLibraryImage(libraryId, imageSize, posX, posY)).withBody(file))
  }

  private def positionImage(user: User, libraryId: PublicId[Library], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    inject[LibraryImageController].positionLibraryImage(libraryId)(request(routes.LibraryImageController.positionLibraryImage(libraryId)).withBody(body))
  }

  private def removeImage(user: User, libraryId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    inject[LibraryImageController].removeLibraryImage(libraryId)(request(routes.LibraryImageController.removeLibraryImage(libraryId)))
  }

  private def request(route: Call) = FakeRequest(route.method, route.url)

  private def fakeFile = {
    val tf = TemporaryFile(new File("test/data/image1-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf.file)
    tf
  }
}

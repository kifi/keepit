package com.keepit.controllers.ext

import java.io.File

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule

import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.crypto.{ FakeCryptoModule, PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.controllers.ext.routes.{ ExtKeepImageController => Routes }
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import org.apache.commons.io.FileUtils

import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile

import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class ExtKeepImageControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

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

  "ExtKeepImageController" should {

    "support image uploads" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1", username = Username("test"), normalizedUsername = "test"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2", username = Username("test"), normalizedUsername = "test"))
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
          (user1, user2, lib, mem1, mem2)
        }
        implicit val config = inject[PublicIdConfiguration]
        val libPubId = Library.publicId(lib.id.get)

        val keepResp = addKeep(user1, libPubId, Json.obj("url" -> "http://www.foo.com", "title" -> "Foo"))
        status(keepResp) === OK
        val keepJson = contentAsJson(keepResp)
        val keepExtId = (keepJson \ "id").as[ExternalId[Keep]]
        val libraryPubId = (keepJson \ "libraryId").as[PublicId[Library]]

        libPubId === libraryPubId

        db.readOnlyMaster { implicit session =>
          keepImageRepo.all() === Seq.empty
        }

        { // Wrong user
          // val uploadResp = uploadFile(user1, libraryPubId, keepExtId, fakeFile)
          // TODO !!!!
        }

        { // Bad keep ID
          val uploadResp = uploadFile(user1, libraryPubId, ExternalId[Keep]("00000000-0000-0000-0000-000000000000"), fakeFile)
          status(uploadResp) === NOT_FOUND
        }

        { // Correct upload
          val uploadResp = uploadFile(user1, libraryPubId, keepExtId, fakeFile)

          status(uploadResp) === OK
          contentAsString(uploadResp) === "\"success\""

          db.readOnlyMaster { implicit session =>
            keepImageRepo.all().head.imagePath.path === "keep/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png"
          }
        }

        1 === 1
      }
    }

    "check status of upload" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1", username = Username("test"), normalizedUsername = "test"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2", username = Username("test"), normalizedUsername = "test"))
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
          (user1, user2, lib, mem1, mem2)
        }
        implicit val config = inject[PublicIdConfiguration]
        val libPubId = Library.publicId(lib.id.get)

        val keepResp = addKeep(user1, libPubId, Json.obj("url" -> "http://www.foo.com", "title" -> "Foo"))
        status(keepResp) === OK
        val keepJson = contentAsJson(keepResp)
        val keepExtId = (keepJson \ "id").as[ExternalId[Keep]]
        val libraryPubId = (keepJson \ "libraryId").as[PublicId[Library]]

        db.readOnlyMaster { implicit session =>
          keepImageRepo.all() === Seq.empty
        }

        { // Wrong user
          // val uploadResp = uploadFile(user1, libraryPubId, keepExtId, fakeFile)
          // TODO !!!!
        }

        { // Bad keep ID
          val uploadResp = checkImageStatus(user1, libraryPubId, keepExtId, "wrongtoken")
          status(uploadResp) === NOT_FOUND
        }

        { // Non-mocked WS
          val changeResp = changeImage(user1, libPubId, keepExtId, Json.obj("image" -> "http://www.bestimages.ever/the_sky.png"))

          status(changeResp) === INTERNAL_SERVER_ERROR

          db.readOnlyMaster { implicit session =>
            keepImageRequestRepo.all().head.failureCode === Some("source_fetch_failed")
          }
        }

        { // Correct request
          // TODO !!!! WS needs to be mocked!
          //        val changeResp = changeImage(user1, libPubId, keepExtId, Json.obj("image" -> "http://www.bestimages.ever/the_sky.png"))
          //
          //        status(changeResp) === INTERNAL_SERVER_ERROR
          //
          //        db.readOnlyMaster { implicit session =>
          //          println(keepImageRequestRepo.all())
          //          keepImageRequestRepo.all() === Seq.empty
          //        }
        }

        1 === 1
      }
    }
  }

  // Disabled for now. We need to be able to mock WS (the remote image fetcher)
  //  "change keep image" in {
  //    withDb(controllerTestModules: _*) { implicit injector =>
  //      val (user1, user2, lib, mem1, mem2) = db.readWrite { implicit s =>
  //        val user1 = userRepo.save(User(firstName = "U", lastName = "1", username = Username("test"), normalizedUsername = "test"))
  //        val user2 = userRepo.save(User(firstName = "U", lastName = "2", username = Username("test"), normalizedUsername = "test"))
  //        val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
  //        val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
  //        val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
  //        (user1, user2, lib, mem1, mem2)
  //      }
  //
  //      implicit val config = inject[PublicIdConfiguration]
  //      val libPubId = Library.publicId(lib.id.get)
  //
  //      val keepResp = addKeep(user1, libPubId, Json.obj("url" -> "http://www.foo.com", "title" -> "Foo"))
  //      status(keepResp) === OK
  //      val keepJson = contentAsJson(keepResp)
  //      val keepExtId = (keepJson \ "id").as[ExternalId[Keep]]
  //      val libraryPubId = (keepJson \ "libraryId").as[PublicId[Library]]
  //
  //      db.readOnlyMaster { implicit session =>
  //        keepImageRepo.all() === Seq.empty
  //      }
  //
  //      val changeResp = changeImage(user1, libPubId, keepExtId, Json.obj("image" -> "http://www.bestimages.ever/the_sky.png"))
  //      println(contentAsString(changeResp))
  //      status(changeResp) === OK
  //
  //
  //
  //      1 === 1
  //    }
  //  }

  private def addKeep(user: User, libraryId: PublicId[Library], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    inject[ExtLibraryController].addKeep(libraryId)(request(routes.ExtLibraryController.addKeep(libraryId)).withBody(body))
  }

  private def uploadFile(user: User, libraryId: PublicId[Library], keepExtId: ExternalId[Keep], file: TemporaryFile)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    inject[ExtKeepImageController].uploadKeepImage(libraryId, keepExtId)(request(routes.ExtKeepImageController.uploadKeepImage(libraryId, keepExtId)).withBody(file))
  }

  private def changeImage(user: User, libraryId: PublicId[Library], keepExtId: ExternalId[Keep], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    inject[ExtKeepImageController].changeKeepImage(libraryId, keepExtId, None)(request(routes.ExtKeepImageController.changeKeepImage(libraryId, keepExtId)).withBody(body))
  }

  private def checkImageStatus(user: User, libraryId: PublicId[Library], keepExtId: ExternalId[Keep], token: String)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    inject[ExtKeepImageController].checkImageStatus(libraryId, keepExtId, token)(request(routes.ExtKeepImageController.checkImageStatus(libraryId, keepExtId, token)))
  }

  private def request(route: Call) = FakeRequest(route.method, route.url)

  private def fakeFile = {
    val tf = TemporaryFile(new File("test/data/image1-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf.file)
    tf
  }
}

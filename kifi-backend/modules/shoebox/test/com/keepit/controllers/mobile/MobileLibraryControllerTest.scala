package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import com.keepit.common.time.internalTime.DateTimeJsonLongFormat
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Result, Call }
import scala.concurrent.Future
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MobileLibraryControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeExternalServiceModule(),
    FakeScraperServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule()
  )

  "MobileLibraryController" should {
    "get library members" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val (userA, userB, userC, userD, lib1) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val userB = userRepo.save(User(firstName = "Baron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val userC = userRepo.save(User(firstName = "Caron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val userD = userRepo.save(User(firstName = "Daron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))

          val lib1 = libraryRepo.save(Library(ownerId = userA.id.get, name = "Lib1", slug = LibrarySlug("lib1"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = userA.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = userB.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true, createdAt = t1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = userC.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true, createdAt = t1.plusMinutes(2)))
          libraryInviteRepo.save(LibraryInvite(inviterId = userB.id.get, libraryId = lib1.id.get, userId = Some(userD.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1.plusMinutes(4)))
          libraryInviteRepo.save(LibraryInvite(inviterId = userB.id.get, libraryId = lib1.id.get, emailAddress = Some(EmailAddress("earon@gmail.com")), access = LibraryAccess.READ_ONLY, createdAt = t1.plusMinutes(6)))

          (userA, userB, userC, userD, lib1)
        }
        val pubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        val result1 = getMembers(userA, pubId1, 0, 2)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        Json.parse(contentAsString(result1)) must equalTo(Json.parse(
          s"""
            |{"members" :
               |[
                 |{
                   |"id":"${userB.externalId}",
                   |"firstName":"Baron",
                   |"lastName":"H",
                   |"pictureName":"0.jpg","username":"test", "active":true,
                   |"membership":"read_only"
                 |},
               |{
                 |"id":"${userC.externalId}",
                 |"firstName":"Caron",
                 |"lastName":"H",
                 |"pictureName":"0.jpg","username":"test", "active":true,
                 |"membership":"read_only"
               |}
               |]
             |}""".stripMargin
        ))

        val result2 = getMembers(userA, pubId1, 2, 2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
            |{"members" :
              |[
                |{
                 |"id":"${userD.externalId}",
                 |"firstName":"Daron",
                 |"lastName":"H",
                 |"pictureName":"0.jpg","username":"test", "active":true,
                 |"membership":"read_only",
                 |"lastInvitedAt":${Json.toJson(t1.plusMinutes(4))}
                |},
                |{
                  |"email":"earon@gmail.com",
                  |"membership":"read_only",
                  |"lastInvitedAt":${Json.toJson(t1.plusMinutes(6))}
                |}
             |]
           |}""".stripMargin
        ))
      }
    }
  }

  private def getMembers(user: User, libId: PublicId[Library], offset: Int, limit: Int)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibraryMembers(libId, offset, limit)(request(routes.MobileLibraryController.getLibraryMembers(libId, offset, limit)))
  }

  private def controller(implicit injector: Injector) = inject[MobileLibraryController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}

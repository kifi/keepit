package com.keepit.controllers.mobile

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.{ LibrarySlug, Username, User, UserConnectionRepo }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import play.api.libs.json.JsObject
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.Future

class MobileMutualUserControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeABookServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeSocialGraphModule()
  )

  "MobileMutualUserController" should {

    "mutual connections" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3, user4) = db.readWrite { implicit s =>
          val user1 = user().withUsername("penguin").saved
          val user2 = user().withUsername("thejoker").saved
          val user3 = user().withUsername("mrfreeze").saved
          val user4 = user().withUsername("poisonivy").saved

          connect().withUsers(user1.id.get, user2.id.get).saved
          connect().withUsers(user1.id.get, user3.id.get).saved
          connect().withUsers(user1.id.get, user4.id.get).saved
          connect().withUsers(user2.id.get, user4.id.get).saved
          connect().withUsers(user3.id.get, user4.id.get).saved

          userRepo.count === 4
          inject[UserConnectionRepo].count === 5
          (user1, user2, user3, user4)
        }

        def getMutualConnections(viewer: User, user: User): Future[Result] = {
          inject[FakeUserActionsHelper].setUser(viewer)
          val path = com.keepit.controllers.mobile.routes.MobileMutualUserController.getMutualConnections(user.externalId).url
          val request = FakeRequest("GET", path)
          inject[MobileMutualUserController].getMutualConnections(user.externalId)(request)
        }

        val testUser2 = getMutualConnections(user1, user2)
        status(testUser2) must equalTo(OK)
        contentType(testUser2) must beSome("application/json")
        val json2 = contentAsJson(testUser2)
        (json2 \\ "username").map(_.as[Username].value) === Seq("poisonivy")
        (json2 \ "totalMutualConnections").as[Int] === 1

        val testUser3 = getMutualConnections(user1, user3)
        status(testUser3) must equalTo(OK)
        contentType(testUser3) must beSome("application/json")
        val json3 = contentAsJson(testUser3)
        (json3 \\ "username").map(_.as[Username].value) === Seq("poisonivy")
        (json3 \ "totalMutualConnections").as[Int] === 1

        val testUser4 = getMutualConnections(user1, user4)
        status(testUser4) must equalTo(OK)
        contentType(testUser4) must beSome("application/json")
        val json4 = contentAsJson(testUser4)
        (json4 \\ "username").map(_.as[Username].value) === Seq("thejoker", "mrfreeze")
        (json4 \ "totalMutualConnections").as[Int] === 2
      }
    }

    "mutual libraries" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3, user4, lib2, lib3) = db.readWrite { implicit s =>
          val user1 = user().withUsername("penguin").saved
          val user2 = user().withUsername("thejoker").saved
          val user3 = user().withUsername("mrfreeze").saved
          val user4 = user().withUsername("poisonivy").saved

          val lib2 = library().withUser(user2).withSlug("gotham-sucks").saved
          val lib3 = library().withUser(user3).withSlug("ice-ice-baby").saved

          membership().withLibraryFollower(lib2, user1).saved
          membership().withLibraryFollower(lib2, user4).saved
          membership().withLibraryFollower(lib3, user1).saved
          membership().withLibraryFollower(lib3, user4).saved

          userRepo.count === 4
          libraryRepo.count === 2
          libraryMembershipRepo.count === 6
          (user1, user2, user3, user4, lib2, lib3)
        }

        def getMutualFollowedLibraries(viewer: User, user: User): Future[Result] = {
          inject[FakeUserActionsHelper].setUser(viewer)
          val path = com.keepit.controllers.mobile.routes.MobileMutualUserController.getMutualLibraries(user.externalId).url
          val request = FakeRequest("GET", path)
          inject[MobileMutualUserController].getMutualLibraries(user.externalId)(request)
        }

        val testLibs1 = getMutualFollowedLibraries(user1, user2)
        status(testLibs1) must equalTo(OK)
        contentType(testLibs1) must beSome("application/json")
        val json1 = contentAsJson(testLibs1)
        (json1 \\ "url").map(_.as[LibrarySlug].value) === Seq() // shouldn't return anything. Even thought user1 follows user2's library, they are not mutually following
        (json1 \ "totalMutualLibraries").as[Int] === 0

        val testLibs3 = getMutualFollowedLibraries(user1, user3)
        status(testLibs3) must equalTo(OK)
        contentType(testLibs3) must beSome("application/json")
        val json3 = contentAsJson(testLibs3)
        (json3 \\ "url").map(_.as[LibrarySlug].value) === Seq()
        (json3 \ "totalMutualLibraries").as[Int] === 0

        val testLibs4 = getMutualFollowedLibraries(user1, user4)
        status(testLibs4) must equalTo(OK)
        contentType(testLibs4) must beSome("application/json")
        val json4 = contentAsJson(testLibs4)
        (json4 \\ "url").map(_.as[LibrarySlug].value) === Seq("/mrfreeze/ice-ice-baby", "/thejoker/gotham-sucks")
        (json4 \ "totalMutualLibraries").as[Int] === 2
      }
    }
  }

}


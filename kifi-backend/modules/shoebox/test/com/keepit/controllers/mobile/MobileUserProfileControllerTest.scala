package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.model.{ UserConnectionRepo, Username, User }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Result, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.Future

class MobileUserProfileControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule()
  )

  "MobileUserProfileController" should {

    "get followers" in {
      withDb(modules: _*) { implicit injector =>
        val profileUsername = Username("cfalc")
        val (user1, user2, user3, user4) = db.readWrite { implicit s =>
          val user1 = user().withName("Captain", "Falcon").withUsername(profileUsername).saved
          val library1 = library().withUser(user1).saved

          val otherUsers = users(3).saved
          membership().withLibraryFollower(library1, otherUsers(0)).saved
          membership().withLibraryFollower(library1, otherUsers(1)).saved
          membership().withLibraryFollower(library1, otherUsers(2)).saved

          connect().withUsers(user1, otherUsers(1)).saved

          userRepo.count === 4
          libraryRepo.count === 1
          libraryMembershipRepo.countWithLibraryId(library1.id.get) === 4
          inject[UserConnectionRepo].count === 1

          (user1, otherUsers(0), otherUsers(1), otherUsers(2))
        }

        val result1 = getProfileFollowers(user1, profileUsername)
        //status(result1) must equalTo(OK)
        //contentAsString(result1) === Json.parse(
        //  s"""
        //     "followers" : {}
        //   """
        //)
        2 === 2

      }
    }
  }

  private def getProfileFollowers(viewer: User, username: Username)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(viewer)
    controller.getProfileFollowers(username)(request(routes.MobileUserProfileController.getProfileFollowers(username)))
  }

  private def controller(implicit injector: Injector) = inject[MobileUserProfileController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}

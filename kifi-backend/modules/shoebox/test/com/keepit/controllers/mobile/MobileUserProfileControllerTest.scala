package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ BasicUserWithFriendStatus, UserConnectionRepo, Username, User }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
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

  def modules = Seq(
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeABookServiceClientModule()
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

        val selfViewer1 = getProfileFollowers(user1, user1.username, 0, 2)
        status(selfViewer1) must equalTo(OK)
        val selfViewerResponse1 = contentAsJson(selfViewer1)
        (selfViewerResponse1 \ "count").as[Int] === 3
        (selfViewerResponse1 \ "ids").as[Seq[ExternalId[User]]].length === 2
        (selfViewerResponse1 \ "users").as[Seq[BasicUserWithFriendStatus]].map(_.externalId) === Seq(user3.externalId, user4.externalId)

        val selfViewer2 = getProfileFollowers(user1, user1.username, 1, 2)
        status(selfViewer2) must equalTo(OK)
        val selfViewerResponse2 = contentAsJson(selfViewer2)
        (selfViewerResponse2 \ "count").as[Int] === 3
        (selfViewerResponse2 \ "ids").as[Seq[ExternalId[User]]].length === 1
        (selfViewerResponse2 \ "users").as[Seq[BasicUserWithFriendStatus]].map(_.externalId) === Seq(user2.externalId)
      }
    }
  }

  private def getProfileFollowers(viewer: User, username: Username, page: Int, size: Int)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(viewer)
    controller.getProfileFollowers(username, page, size)(request(routes.MobileUserProfileController.getProfileFollowers(username, page, size)))
  }

  private def controller(implicit injector: Injector) = inject[MobileUserProfileController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}

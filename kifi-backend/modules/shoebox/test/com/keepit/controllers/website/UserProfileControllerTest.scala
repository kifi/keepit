package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.FriendStatusCommander
import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.LibraryInviteFactory._
import com.keepit.model.LibraryInviteFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model._
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector

import org.specs2.mutable.Specification

import play.api.libs.json.{ JsArray, JsNumber, JsString, Json }
import play.api.test.Helpers._
import play.api.test._

import scala.slick.jdbc.StaticQuery

class UserProfileControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeScrapeSchedulerModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "UserProfileController" should {

    "get profile connections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5, lib1) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          connect(user1 -> user2,
            user1 -> user3,
            user4 -> user1,
            user2 -> user3).saved

          val user1secretLib = libraries(3).map(_.withUser(user1).secret()).saved.head.savedFollowerMembership(user2)

          val user1lib = library().withUser(user1).published().saved.savedFollowerMembership(user5, user4)
          user1lib.visibility === LibraryVisibility.PUBLISHED

          val user3lib = library().withUser(user3).published().saved.savedFollowerMembership(user2)
          val user5lib = library().withUser(user5).published().saved.savedFollowerMembership(user1)
          membership().withLibraryFollower(library().withUser(user5).published().saved, user1).unlisted().saved

          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved
          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved // duplicate library invite
          invite().fromLibraryOwner(user5lib).toUser(user1.id.get).withState(LibraryInviteStates.ACCEPTED).saved

          keeps(2).map(_.withLibrary(user1secretLib)).saved
          keeps(3).map(_.withLibrary(user1lib)).saved
          keep().withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib)
        }
        val controller = inject[UserProfileController]
        def basicUserWFS(user: User, viewerIdOpt: Option[Id[User]])(implicit session: RSession): BasicUserWithFriendStatus = {
          val basicUser = BasicUser.fromUser(user)
          viewerIdOpt map { viewerId =>
            inject[FriendStatusCommander].augmentUser(viewerId, user.id.get, basicUser)
          } getOrElse BasicUserWithFriendStatus.fromWithoutFriendStatus(basicUser)
        }
        db.readOnlyMaster { implicit s =>
          controller.loadProfileUser(user1.id.get, basicUserWFS(user1, user2.id), user2.id) === Json.obj(
            "id" -> user1.externalId,
            "firstName" -> user1.firstName,
            "lastName" -> user1.lastName,
            "pictureName" -> "pic1.jpg",
            "username" -> user1.username.value,
            "isFriend" -> true,
            "libraries" -> 2, "connections" -> 3, "followers" -> 3,
            "mLibraries" -> 0, "mConnections" -> 1
          )
          controller.loadProfileUser(user1.id.get, basicUserWFS(user1, None), None) === Json.obj(
            "id" -> user1.externalId,
            "firstName" -> user1.firstName,
            "lastName" -> user1.lastName,
            "pictureName" -> "pic1.jpg",
            "username" -> user1.username.value,
            "libraries" -> 1,
            "connections" -> 3, "followers" -> 2
          )
          controller.loadProfileUser(user2.id.get, basicUserWFS(user2, user3.id), user3.id) === Json.obj(
            "id" -> user2.externalId,
            "firstName" -> user2.firstName,
            "lastName" -> user2.lastName,
            "pictureName" -> "0.jpg",
            "username" -> user2.username.value,
            "isFriend" -> true,
            "libraries" -> 0, "connections" -> 2, "followers" -> 0,
            "mLibraries" -> 1, "mConnections" -> 1
          )
          controller.loadProfileUser(user2.id.get, basicUserWFS(user2, None), None) === Json.obj(
            "id" -> user2.externalId,
            "firstName" -> user2.firstName,
            "lastName" -> user2.lastName,
            "pictureName" -> "0.jpg",
            "username" -> user2.username.value,
            "libraries" -> 0, "connections" -> 2, "followers" -> 0
          )
        }
      }
    }

    "get profile libraries" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5, lib1) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          connect(user1 -> user2,
            user1 -> user3,
            user4 -> user1,
            user2 -> user3).saved

          val user1secretLib = libraries(3).map(_.withUser(user1).secret()).saved.head.savedFollowerMembership(user2)

          val user1lib = library().withUser(user1).published().saved.savedFollowerMembership(user5, user4)
          user1lib.visibility === LibraryVisibility.PUBLISHED

          val user3lib = library().withUser(user3).published().saved
          val user5lib = library().withUser(user5).published().saved.savedFollowerMembership(user1)
          membership().withLibraryFollower(library().withUser(user5).published().saved, user1).unlisted().saved

          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved
          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved // duplicate library invite
          invite().fromLibraryOwner(user5lib).toUser(user1.id.get).withState(LibraryInviteStates.ACCEPTED).saved

          keeps(2).map(_.withLibrary(user1secretLib)).saved
          keeps(3).map(_.withLibrary(user1lib)).saved
          keep().withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib)
        }
        db.readOnlyMaster { implicit s =>
          val libMem = libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user4.id.get).get
          libMem.access === LibraryAccess.READ_ONLY
          libMem.state.value === "active"

          import StaticQuery.interpolation
          val ret1 = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = 4".as[Int].firstOption.getOrElse(0)
          ret1 === 1
          val ret2 = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = 4 and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published'".as[Int].firstOption.getOrElse(0)
          ret2 === 1

          libraryMembershipRepo.countWithUserIdAndAccess(user1.id.get, LibraryAccess.OWNER) === 4
          libraryMembershipRepo.countWithUserIdAndAccess(user2.id.get, LibraryAccess.OWNER) === 0
          libraryMembershipRepo.countWithUserIdAndAccess(user3.id.get, LibraryAccess.OWNER) === 1
          libraryMembershipRepo.countWithUserIdAndAccess(user4.id.get, LibraryAccess.OWNER) === 0
          libraryMembershipRepo.countWithUserIdAndAccess(user5.id.get, LibraryAccess.OWNER) === 2

          libraryRepo.countLibrariesOfUserFromAnonymous(user1.id.get) === 1
          libraryRepo.countLibrariesOfUserFromAnonymous(user2.id.get) === 0
          libraryRepo.countLibrariesOfUserFromAnonymous(user3.id.get) === 1
          libraryRepo.countLibrariesOfUserFromAnonymous(user4.id.get) === 0
          libraryRepo.countLibrariesOfUserFromAnonymous(user5.id.get) === 2
          libraryRepo.countLibrariesForOtherUser(user1.id.get, user5.id.get) === 1
          libraryRepo.countLibrariesForOtherUser(user1.id.get, user2.id.get) === 2
          libraryRepo.countLibrariesForOtherUser(user2.id.get, user5.id.get) === 0
          libraryRepo.countLibrariesForOtherUser(user3.id.get, user5.id.get) === 1
          libraryRepo.countLibrariesForOtherUser(user4.id.get, user5.id.get) === 0
          libraryRepo.countLibrariesForOtherUser(user5.id.get, user1.id.get) === 2
        }
        val controller = inject[UserProfileController]
        def call(viewer: Option[User], viewing: Username) = {
          viewer match {
            case None => inject[FakeUserActionsHelper].unsetUser()
            case Some(user) => inject[FakeUserActionsHelper].setUser(user)
          }
          val request = FakeRequest("GET", routes.UserProfileController.profile(viewing).url)
          controller.profile(viewing)(request)
        }
        //non existing username
        status(call(Some(user1), Username("foo"))) must equalTo(NOT_FOUND)

        //seeing a profile from an anonymos user
        val anonViewer = call(None, user1.username)
        status(anonViewer) must equalTo(OK)
        contentType(anonViewer) must beSome("application/json")
        contentAsJson(anonViewer) === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "numLibraries": 1,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 2
            }
          """)

        //seeing a profile of my own
        val selfViewer = call(Some(user1), user1.username)
        status(selfViewer) must equalTo(OK)
        contentType(selfViewer) must beSome("application/json")
        val res2 = contentAsJson(selfViewer)
        res2 === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "numLibraries": 4,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3,
              "numInvitedLibraries": 1
            }
          """)

        //seeing a profile from another user (friend)
        val friendViewer = call(Some(user2), user1.username)
        status(friendViewer) must equalTo(OK)
        contentType(friendViewer) must beSome("application/json")
        val res3 = contentAsJson(friendViewer)
        res3 === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "isFriend": true,
              "numLibraries": 2,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3
            }
          """)
      }
    }

    "get profile followers" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          libraries(3).map(_.withUser(user1).secret()).saved.head.savedFollowerMembership(user2, user3) // create 3 secret libraries, one is follower by user2 and user3
          library().withUser(user1).published().saved.savedFollowerMembership(user5, user4) // create 1 published library, followed by user4 and user 5

          (user1, user2, user3, user4, user5)
        }
        val controller = inject[UserProfileController]

        // view as owner
        inject[FakeUserActionsHelper].setUser(user1)
        val result1 = controller.getProfileFollowers(Username("GDubs"), 2)(FakeRequest("GET", routes.UserProfileController.getProfileFollowers(Username("GDubs"), 2).url))
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson1 = contentAsJson(result1)
        (resultJson1 \ "count") === JsNumber(4)
        (resultJson1 \\ "id") === Seq(user2, user3).map(u => JsString(u.externalId.id))
        (resultJson1 \ "ids") === Json.toJson(Seq(user4, user5).map(_.externalId))

        // view as anybody
        inject[FakeUserActionsHelper].setUser(user4)
        val result2 = controller.getProfileFollowers(Username("GDubs"), 10)(FakeRequest("GET", routes.UserProfileController.getProfileFollowers(Username("GDubs"), 10).url))
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val resultJson2 = contentAsJson(result2)
        (resultJson2 \ "count") === JsNumber(2)
        (resultJson2 \\ "id") === Seq(user4, user5).map(u => JsString(u.externalId.id))
        (resultJson2 \ "ids") === JsArray()

        // view as follower (to a secret library)
        inject[FakeUserActionsHelper].setUser(user2)
        val result3 = controller.getProfileFollowers(Username("GDubs"), 10)(FakeRequest("GET", routes.UserProfileController.getProfileFollowers(Username("GDubs"), 10).url))
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")
        val resultJson3 = contentAsJson(result3)
        (resultJson3 \ "count") === JsNumber(3)
        (resultJson3 \\ "id") === Seq(user4, user5, user2).map(u => JsString(u.externalId.id))
        (resultJson3 \ "ids") === JsArray()
      }
    }

  }

}

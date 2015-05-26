package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model.KeepFactory._
import com.keepit.model._
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
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc.{ Result, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.Future

class MobileUserProfileControllerTest extends Specification with ShoeboxTestInjector {

  def modules = Seq(
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeCryptoModule()
  )

  "MobileUserProfileController" should {

    "get profile for self" in {
      withDb(modules: _*) { implicit injector =>
        val userConnectionRepo = inject[UserConnectionRepo]
        val (user1, user2, user3, user4, user5, lib1) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          inject[UserValueRepo].save(UserValue(userId = user1.id.get, name = UserValueName.USER_DESCRIPTION, value = "First Prez yo!"))
          connect(user1 -> user2,
            user1 -> user3,
            user4 -> user1,
            user2 -> user3).saved

          val user1secretLib = libraries(3).map(_.withUser(user1).secret().withKeepCount(3)).saved.head.savedFollowerMembership(user2)

          val user1lib = library().withUser(user1).published().saved.savedFollowerMembership(user5, user4)
          user1lib.visibility === LibraryVisibility.PUBLISHED

          val user3lib = library().withUser(user3).published().withKeepCount(2).saved
          val user5lib = library().withUser(user5).published().withKeepCount(4).saved.savedFollowerMembership(user1)
          membership().withLibraryFollower(library().withUser(user5).published().withKeepCount(1).saved, user1).unlisted().saved

          keeps(2).map(_.withLibrary(user1secretLib)).saved
          keeps(3).map(_.withLibrary(user1lib)).saved
          keep().withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib)
        }

        //non existing username
        status(getProfile(Some(user1), Username("foo"))) must equalTo(NOT_FOUND)

        //seeing a profile from an anonymos user
        val anonViewer = getProfile(None, user1.username)
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
              "numLibraries":1,
              "numFollowedLibraries": 1,
              "numCollabLibraries": 0,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 2,
              "biography":"First Prez yo!"
            }
          """)

        //seeing a profile from another user (friend)
        val friendViewer = getProfile(Some(user2), user1.username)
        status(friendViewer) must equalTo(OK)
        contentType(friendViewer) must beSome("application/json")
        contentAsJson(friendViewer) === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "isFriend": true,
              "numLibraries":2,
              "numFollowedLibraries": 1,
              "numCollabLibraries": 1,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3,
              "biography":"First Prez yo!"
            }
          """)

        //seeing a profile of my own
        val selfViewer = getProfile(Some(user1), user1.username)
        status(selfViewer) must equalTo(OK)
        contentType(selfViewer) must beSome("application/json")
        contentAsJson(selfViewer) === Json.parse(
          s"""
            {
              "id":"${user1.externalId.id}",
              "firstName":"George",
              "lastName":"Washington",
              "pictureName":"pic1.jpg",
              "username": "GDubs",
              "numLibraries":4,
              "numFollowedLibraries": 2,
              "numCollabLibraries": 0,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3,
              "numInvitedLibraries": 0,
              "biography":"First Prez yo!"
            }
          """)
      }
    }

    "get profile libraries" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, lib1, lib2) = db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 12, 1, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val user1 = userRepo.save(User(firstName = "Spongebob", lastName = "Squarepants", username = Username("spongebob"), normalizedUsername = "spongebob", createdAt = t1))
          val library1 = libraryRepo.save(Library(name = "Krabby Patty", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("krabby-patty"), memberCount = 1, createdAt = t1.plusMinutes(1)))
          libraryMembershipRepo.save(LibraryMembership(userId = user1.id.get, libraryId = library1.id.get, access = LibraryAccess.OWNER))
          val library2 = libraryRepo.save(Library(name = "Catching Jellyfish", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("catching-jellyfish"), memberCount = 1, createdAt = t1.plusMinutes(1)))
          libraryMembershipRepo.save(LibraryMembership(userId = user1.id.get, libraryId = library2.id.get, access = LibraryAccess.OWNER))
          (user1, library1, library2)
        }
        val pubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val pubId2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])

        val result1 = getProfileLibraries(user1, 0, 10, "own")
        status(result1) must equalTo(OK)
        Json.parse(contentAsString(result1)) === Json.parse(
          s"""
             {
              "own" : [
                {
                  "id":"${pubId2.id}",
                  "name":"Catching Jellyfish",
                  "numFollowers":0,
                  "numKeeps":0,
                  "followers":[],
                  "slug":"catching-jellyfish",
                  "kind" : "user_created",
                  "visibility" : "published",
                  "owner": {
                    "id":"${user1.externalId.id}",
                    "firstName":"Spongebob",
                    "lastName":"Squarepants",
                    "pictureName":"0.jpg",
                    "username":"spongebob"
                  },
                  "numKeeps" : 0,
                  "numFollowers" : 0,
                  "followers": [],
                  "numCollaborators":0,
                  "collaborators":[],
                  "lastKept": ${lib2.createdAt.getMillis},
                  "listed": true,
                  "following":true,
                  "membership":{"access":"owner","listed":true,"subscription":false},
                  "modifiedAt":${lib2.updatedAt.getMillis}
                },
                {
                  "id":"${pubId1.id}",
                  "name":"Krabby Patty",
                  "numFollowers":0,
                  "numKeeps":0,
                  "followers":[],
                  "slug":"krabby-patty",
                  "kind" : "user_created",
                  "visibility" : "secret",
                  "owner": {
                    "id":"${user1.externalId.id}",
                    "firstName":"Spongebob",
                    "lastName":"Squarepants",
                    "pictureName":"0.jpg",
                    "username":"spongebob"
                  },
                  "numKeeps" : 0,
                  "numFollowers" : 0,
                  "followers": [],
                  "numCollaborators": 0,
                  "collaborators": [],
                  "lastKept": ${lib1.createdAt.getMillis},
                  "listed": true,
                  "following":true,
                  "membership":{"access":"owner","listed":true,"subscription":false},
                  "modifiedAt":${lib1.updatedAt.getMillis}
                }
              ]
            }
           """)
        val result2 = getProfileLibraries(user1, 0, 10, "all")
        status(result2) must equalTo(OK)
        val resultJson2 = contentAsJson(result2)
        (resultJson2 \ "own").as[Seq[JsObject]].length === 2
        (resultJson2 \ "following").as[Seq[JsObject]].length === 0
        (resultJson2 \ "invited").as[Seq[JsObject]].length === 0
      }
    }

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

  private def getProfile(viewer: Option[User], username: Username)(implicit injector: Injector): Future[Result] = {
    viewer match {
      case None => inject[FakeUserActionsHelper].unsetUser()
      case Some(user) => inject[FakeUserActionsHelper].setUser(user)
    }
    val url = routes.MobileUserProfileController.profile(username.value).url
    url === s"/m/1/user/${username.value}/profile"
    val request = FakeRequest("GET", url)
    controller.profile(username.value)(request)
  }

  private def getProfileLibraries(user: User, page: Int, size: Int, filter: String)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getProfileLibraries(user.username, page, size, filter)(request(routes.MobileUserProfileController.getProfileLibraries(user.username, page, size, filter)))
  }

  private def getProfileFollowers(viewer: User, username: Username, page: Int, size: Int)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(viewer)
    controller.getProfileFollowers(username, page, size)(request(routes.MobileUserProfileController.getProfileFollowers(username, page, size)))
  }

  private def controller(implicit injector: Injector) = inject[MobileUserProfileController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}

package com.keepit.controllers.mobile

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller._
import com.keepit.common.db._
import com.keepit.common.db.slick._

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social._
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.website.UserController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.KeepFactory._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.UserFactory._
import com.keepit.model.{ UserConnection, _ }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.SocialNetworks._
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector, ShoeboxTestInjector }
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.Play
import play.api.libs.json._
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core.providers.utils.{ BCryptPasswordHasher, PasswordHasher }
import securesocial.core.{ IdentityId, _ }

import scala.concurrent.Future
import scala.slick.jdbc.StaticQuery

class MobileUserControllerTest extends Specification with ShoeboxApplicationInjector {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeScrapeSchedulerModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeShoeboxAppSecureSocialModule(),
    FakeCuratorServiceClientModule()
  )

  "mobileController" should {

    "change user password" in {
      running(new ShoeboxApplication(mobileControllerTestModules: _*)) {
        val bcrypt = Registry.hashers.get(PasswordHasher.BCryptHasher) getOrElse (new BCryptPasswordHasher(Play.current))
        val changePwdRoute = routes.MobileUserController.changePassword().toString
        changePwdRoute === "/m/1/password/change"
        inject[Database].readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Richard", lastName = "Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672"), username = Username("test"), normalizedUsername = "test"))
          val identityId = IdentityId("me@feynman.com", "userpass")
          val pInfo = bcrypt.hash("welcome")
          val socialUser = new SocialUser(identityId, "Richard", "Feynman", "Richard Feynman", Some(identityId.userId), None, AuthenticationMethod.UserPassword, passwordInfo = Some(pInfo))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("me@feynman.com"), networkType = FORTYTWO, credentials = Some(socialUser)))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("FRF"), networkType = FACEBOOK))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("LRF"), networkType = LINKEDIN,
            profileUrl = Some("http://www.linkedin.com/in/rf"), pictureUrl = Some("http://my.pic.com/pic.jpg")))
          inject[FakeUserActionsHelper].setUser(user)
        }

        val payload = Json.obj("oldPassword" -> "welcome", "newPassword" -> "welcome1")

        // positive
        val changePwdRequest = FakeRequest("POST", changePwdRoute).withJsonBody(payload)
        val result = route(changePwdRequest).get
        status(result) === OK
        contentType(result) must beSome("application/json")
        val expected = Json.obj("code" -> "password_changed")
        Json.parse(contentAsString(result)) must equalTo(expected)

        // negative
        val changePwdRequest2 = FakeRequest("POST", changePwdRoute).withJsonBody(payload)
        val result2 = route(changePwdRequest2).get
        status(result2) !== OK
        contentType(result2) must beSome("application/json")
        val expected2 = Json.obj("code" -> "bad_old_password")
        Json.parse(contentAsString(result2)) must equalTo(expected2)
      }
    }

  }
}

class FasterMobileUserControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeScrapeSchedulerModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule()
  )

  def setupSomeUsers()(implicit injector: Injector) = {
    inject[Database].readWrite { implicit s =>
      val user1965 = userRepo.save(User(firstName = "Richard", lastName = "Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672"), username = Username("test"), normalizedUsername = "test"))
      val user1933 = userRepo.save(User(firstName = "Paul", lastName = "Dirac", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a673"), username = Username("test"), normalizedUsername = "test"))
      val user1935 = userRepo.save(User(firstName = "James", lastName = "Chadwick", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a674"), username = Username("test"), normalizedUsername = "test"))
      val user1927 = userRepo.save(User(firstName = "Arthur", lastName = "Compton", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a675"), username = Username("test"), normalizedUsername = "test"))
      val user1921 = userRepo.save(User(firstName = "Albert", lastName = "Einstein", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a676"), username = Username("test"), normalizedUsername = "test"))
      val friends = List(user1933, user1935, user1927, user1921)

      val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
      friends.zipWithIndex.foreach { case (friend, i) => userConnRepo.save(UserConnection(user1 = user1965.id.get, user2 = friend.id.get, createdAt = now.plusDays(i))) }
      (user1965, friends)
    }
  }

  "FasterMobileUserControllerTest" should {

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

          val user1secretLib = libraries(3).map(_.withUser(user1).secret()).saved.head.savedFollowerMembership(user2)

          val user1lib = library().withUser(user1).published().saved.savedFollowerMembership(user5, user4)
          user1lib.visibility === LibraryVisibility.PUBLISHED

          val user3lib = library().withUser(user3).published().saved
          val user5lib = library().withUser(user5).published().saved.savedFollowerMembership(user1)
          membership().withLibraryFollower(library().withUser(user5).published().saved, user1).unlisted().saved

          keeps(2).map(_.withLibrary(user1secretLib)).saved
          keeps(3).map(_.withLibrary(user1lib)).saved
          keep().withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib)
        }

        val userController = inject[MobileUserController]
        def call(viewer: Option[User], viewing: Username) = {
          viewer match {
            case None => inject[FakeUserActionsHelper].unsetUser()
            case Some(user) => inject[FakeUserActionsHelper].setUser(user)
          }
          val url = routes.MobileUserController.profile(viewing.value).url
          url === s"/m/1/user/${viewing.value}/profile"
          val request = FakeRequest("GET", url)
          userController.profile(viewing.value)(request)
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
              "numLibraries":1,
              "numFollowedLibraries": 1,
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 2,
              "biography":"First Prez yo!"
            }
          """)

        //seeing a profile from another user (friend)
        val friendViewer = call(Some(user2), user1.username)
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
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3,
              "biography":"First Prez yo!"
            }
          """)

        //seeing a profile of my own
        val selfViewer = call(Some(user1), user1.username)
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
              "numKeeps": 5,
              "numConnections": 3,
              "numFollowers": 3,
              "numInvitedLibraries": 0,
              "biography":"First Prez yo!"
            }
          """)
      }
    }

    "get currentUser" in {
      withDb(modules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Shanee", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        }

        val ctrl = routes.MobileUserController.currentUser()
        ctrl.toString === "/m/1/user/me"
        ctrl.method === "GET"

        val controller = inject[MobileUserController]
        inject[FakeUserActionsHelper].setUser(user, Set(ExperimentType.ADMIN))

        val call = controller.currentUser
        val result = call(FakeRequest())
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Shanee",
              "lastName":"Smith",
              "pictureName":"0.jpg","username":"test",
              "emails":[],
              "notAuthed":[],
              "experiments":["admin"],
              "clickCount":0,
              "rekeepCount":0,
              "rekeepTotalCount":0,
              "friendCount": 0,
              "keepCount": 0,
              "libCount": 0,
              "libFollowerCount": 0
            }
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "get basic user info for any user" in {
      withDb(modules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit rw =>
          inject[UserRepo].save(User(firstName = "James", lastName = "Franco", username = Username("test"), normalizedUsername = "test"))
        }

        inject[FakeUserActionsHelper].setUser(user)

        val ctrl = routes.MobileUserController.basicUserInfo(user.externalId, true)
        ctrl.toString === s"/m/1/user/${user.externalId}?friendCount=true"
        ctrl.method === "GET"

        val call = inject[MobileUserController].basicUserInfo(user.externalId, true)
        val result = call(FakeRequest())

        val body = contentAsString(result)
        contentType(result).get must beEqualTo("application/json")
        body must contain("id\":\"" + user.externalId)
        body must contain("firstName\":\"James")
        body must contain("lastName\":\"Franco")
        body must contain("friendCount\":0")
      }
    }

    "updateCurrentUser updates names" in {
      withDb(modules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Sam", lastName = "Jackson", username = Username("test"), normalizedUsername = "test"))
        }

        val ctrl = routes.MobileUserController.updateCurrentUser()
        ctrl.toString === "/m/1/user/me"
        ctrl.method === "POST"

        val controller = inject[MobileUserController]
        inject[FakeUserActionsHelper].setUser(user, Set())

        val bodyInput = Json.parse("""
          {
            "firstName": "Donald",
            "lastName": "Trump",
            "biography": "I am rich and famous"
          }""")

        val call = controller.updateCurrentUser()
        val result = call(FakeRequest().withBody(bodyInput))

        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Donald",
              "lastName":"Trump",
              "pictureName":"0.jpg",
              "username":"test",
              "biography":"I am rich and famous",
              "emails":[],
              "notAuthed":[],
              "experiments":[],
              "clickCount":0,
              "rekeepCount":0,
              "rekeepTotalCount":0,
              "friendCount": 0,
              "keepCount": 0,
              "libCount": 0,
              "libFollowerCount": 0
            }
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)
      }

    }

    "return connected users from the database" in {
      withDb(modules: _*) { implicit injector =>
        val route = routes.MobileUserController.friends().toString
        route === "/m/1/user/friendsDetails"

        val (user1965, friends) = setupSomeUsers()
        inject[FakeUserActionsHelper].setUser(user1965)
        val mobileController = inject[MobileUserController]
        val result = mobileController.friends(0, 1000)(FakeRequest())
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse(
          """{"friends":[
              {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a676","firstName":"Albert","lastName":"Einstein","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false},
              {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a675","firstName":"Arthur","lastName":"Compton","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false},
              {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a674","firstName":"James","lastName":"Chadwick","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false},
              {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a673","firstName":"Paul","lastName":"Dirac","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false}
            ],
            "total":4}""")
        val resString = contentAsString(result)
        // println(resString) // can be removed?
        val res = Json.parse(resString)
        res must equalTo(expected)
      }
    }

    "get socialNetworkInfo" in {
      withDb(modules: _*) { implicit injector =>
        val route = routes.MobileUserController.socialNetworkInfo().toString
        route === "/m/1/user/networks"
        inject[Database].readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Richard", lastName = "Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672"), username = Username("test"), normalizedUsername = "test"))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("FRF"), networkType = SocialNetworks.FACEBOOK))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("LRF"), networkType = SocialNetworks.LINKEDIN,
            profileUrl = Some("http://www.linkedin.com/in/rf"), pictureUrl = Some("http://my.pic.com/pic.jpg")))
          inject[FakeUserActionsHelper].setUser(user)
        }
        val mobileController = inject[MobileUserController]
        val result = mobileController.socialNetworkInfo()(FakeRequest())
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse("""[
            {"network":"facebook","profileUrl":"http://facebook.com/FRF","pictureUrl":"https://graph.facebook.com/FRF/picture?width=50&height=50"},
            {"network":"linkedin","profileUrl":"http://www.linkedin.com/in/rf","pictureUrl":"http://my.pic.com/pic.jpg"}
          ]""")
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "set user profile settings" in {
      withDb(modules: _*) { implicit injector =>
        val mobileController = inject[MobileUserController]
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "George", lastName = "Washington", username = Username("GDubs"), normalizedUsername = "gdubs"))
        }

        inject[FakeUserActionsHelper].setUser(user)
        val userController = inject[UserController]
        val getPath = routes.MobileUserController.getSettings().url
        val setPath = routes.MobileUserController.setSettings().url

        // initial getSettings
        val request1 = FakeRequest("GET", getPath)
        val result1: Future[Result] = mobileController.getSettings()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        contentAsString(result1) === s"""{"showFollowedLibraries":true}"""

        // set settings (showFollowedLibraries to false)
        val request2 = FakeRequest("POST", setPath).withBody(Json.obj("showFollowedLibraries" -> false))
        val result2: Future[Result] = mobileController.setSettings()(request2)
        status(result2) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.USER_PROFILE_SETTINGS).get === s"""{"show_followed_libraries":false}"""
        }

        // get settings
        val request3 = FakeRequest("GET", getPath)
        val result3: Future[Result] = mobileController.getSettings()(request3)
        status(result3) must equalTo(OK)
        contentAsString(result3) === s"""{"showFollowedLibraries":false}"""

        // reset settings (showFollowedLibraries to true)
        val request4 = FakeRequest("POST", setPath).withBody(Json.obj("showFollowedLibraries" -> true))
        val result4: Future[Result] = mobileController.setSettings()(request4)
        status(result4) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.USER_PROFILE_SETTINGS).get === s"""{"show_followed_libraries":true}"""
        }
      }
    }
  }
}

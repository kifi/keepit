package com.keepit.controllers.mobile

import com.keepit.commanders.UserIpAddressEventLogger
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time._
import com.keepit.model.UserFactoryHelper._
import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller._
import com.keepit.common.db.slick._

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social._
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.website.UserController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model.{ UserConnection, _ }
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

class MobileUserControllerTest extends Specification with ShoeboxApplicationInjector {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
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
    FakeShoeboxAppSecureSocialModule()
  )

  "mobileController" should {

    "change user password" in {
      running(new ShoeboxApplication(mobileControllerTestModules: _*)) {
        val bcrypt = Registry.hashers.get(PasswordHasher.BCryptHasher) getOrElse (new BCryptPasswordHasher(Play.current))
        val changePwdRoute = routes.MobileUserController.changePassword().toString
        changePwdRoute === "/m/1/password/change"
        inject[Database].readWrite { implicit s =>
          val user = UserFactory.user().withName("Richard", "Feynman").withUsername("test").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672").saved
          val pInfo = bcrypt.hash("welcome")
          userCredRepo.internUserPassword(user.id.get, pInfo.password)
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
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeSocialGraphModule()
  )

  def setupSomeUsers()(implicit injector: Injector) = {
    inject[Database].readWrite { implicit s =>
      val user1965 = UserFactory.user().withName("Richard", "Feynman").withUsername("test").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672").saved
      val user1933 = UserFactory.user().withName("Paul", "Dirac").withUsername("test").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a673").saved
      val user1935 = UserFactory.user().withName("James", "Chadwick").withUsername("test").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a674").saved
      val user1927 = UserFactory.user().withName("Arthur", "Compton").withUsername("test").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a675").saved
      val user1921 = UserFactory.user().withName("Albert", "Einstein").withUsername("test").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a676").saved

      val friends = List(user1933, user1935, user1927, user1921)

      val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
      friends.zipWithIndex.foreach { case (friend, i) => userConnRepo.save(UserConnection(user1 = user1965.id.get, user2 = friend.id.get, createdAt = now.plusDays(i))) }
      (user1965, friends)
    }
  }

  "FasterMobileUserControllerTest" should {

    "get currentUser" in {
      withDb(modules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          UserFactory.user().withName("Shanee", "Smith").withUsername("test").saved
        }

        val ctrl = routes.MobileUserController.currentUser()
        ctrl.toString === "/m/1/user/me"
        ctrl.method === "GET"

        val controller = inject[MobileUserController]
        inject[FakeUserActionsHelper].setUser(user, Set(UserExperimentType.ADMIN))

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
          UserFactory.user().withName("James", "Franco").withUsername("test").saved
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
          UserFactory.user().withName("Sam", "Jackson").withUsername("test").saved
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
          val user = UserFactory.user().withName("Richard", "Feynman").withUsername("test").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672").saved
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("314159265359"), networkType = SocialNetworks.FACEBOOK))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("271828"), networkType = SocialNetworks.LINKEDIN,
            profileUrl = Some("http://www.linkedin.com/in/rf"), pictureUrl = Some("http://my.pic.com/pic.jpg")))
          inject[FakeUserActionsHelper].setUser(user)
        }
        val mobileController = inject[MobileUserController]
        val result = mobileController.socialNetworkInfo()(FakeRequest())
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse("""[
            {"network":"facebook","profileUrl":"https://www.facebook.com/314159265359","pictureUrl":"https://graph.facebook.com/v2.0/314159265359/picture?width=50&height=50"},
            {"network":"linkedin","profileUrl":"http://www.linkedin.com/in/rf","pictureUrl":"http://my.pic.com/pic.jpg"}
          ]""")
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "set user profile settings" in {
      withDb(modules: _*) { implicit injector =>
        val mobileController = inject[MobileUserController]
        val user = db.readWrite { implicit session =>
          UserFactory.user().withName("George", "Washington").withUsername("GDubs").saved
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
        contentAsString(result1) === s"""{"showFollowedLibraries":true,"leftHandRailSort":"last_kept_into"}"""

        // set settings (showFollowedLibraries to false)
        val request2 = FakeRequest("POST", setPath).withBody(Json.obj("showFollowedLibraries" -> false))
        val result2: Future[Result] = mobileController.setSettings()(request2)
        status(result2) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.USER_PROFILE_SETTINGS).get === s"""{"showFollowedLibraries":false,"leftHandRailSort":"last_kept_into"}"""
        }

        // get settings
        val request3 = FakeRequest("GET", getPath)
        val result3: Future[Result] = mobileController.getSettings()(request3)
        status(result3) must equalTo(OK)
        contentAsString(result3) === s"""{"showFollowedLibraries":false,"leftHandRailSort":"last_kept_into"}"""

        // reset settings (showFollowedLibraries to true)
        val request4 = FakeRequest("POST", setPath).withBody(Json.obj("showFollowedLibraries" -> true))
        val result4: Future[Result] = mobileController.setSettings()(request4)
        status(result4) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          inject[UserValueRepo].getValueStringOpt(user.id.get, UserValueName.USER_PROFILE_SETTINGS).get === s"""{"showFollowedLibraries":true,"leftHandRailSort":"last_kept_into"}"""
        }
      }
    }
  }
}

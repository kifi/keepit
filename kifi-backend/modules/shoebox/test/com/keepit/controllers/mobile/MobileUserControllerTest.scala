package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social._
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ UserConnection, _ }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.SocialNetworks._
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.Play
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core.providers.utils.{ BCryptPasswordHasher, PasswordHasher }
import securesocial.core.{ IdentityId, _ }

class MobileUserControllerTest extends Specification with ShoeboxApplicationInjector {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
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
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeShoeboxAppSecureSocialModule(),
    FakeCuratorServiceClientModule()
  )

  "mobileController" should {

    "change user password" in {
      running(new ShoeboxApplication(mobileControllerTestModules: _*)) {
        val bcrypt = Registry.hashers.get(PasswordHasher.BCryptHasher) getOrElse (new BCryptPasswordHasher(Play.current))
        val changePwdRoute = com.keepit.controllers.mobile.routes.MobileUserController.changePassword().toString
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
    FakeScrapeSchedulerModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeABookServiceClientModule(),
    FakeExternalServiceModule(),
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

      friends.foreach { friend => userConnRepo.save(UserConnection(user1 = user1965.id.get, user2 = friend.id.get)) }
      (user1965, friends)
    }
  }

  "MobileUserControllerTest" should {

    "get currentUser" in {
      withDb(modules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Shanee", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        }

        val ctrl = com.keepit.controllers.mobile.routes.MobileUserController.currentUser()
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
              "pictureName":"0.jpg","username":"test", "active":true,
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

        val ctrl = com.keepit.controllers.mobile.routes.MobileUserController.basicUserInfo(user.externalId, true)
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

        val ctrl = com.keepit.controllers.mobile.routes.MobileUserController.updateCurrentUser()
        ctrl.toString === "/m/1/user/me"
        ctrl.method === "POST"

        val controller = inject[MobileUserController]
        inject[FakeUserActionsHelper].setUser(user, Set())

        val bodyInput = Json.parse("""
          {
            "firstName": "Donald",
            "lastName": "Trump"
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
              "pictureName":"0.jpg","username":"test", "active":true,
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
        val route = com.keepit.controllers.mobile.routes.MobileUserController.friends().toString
        route === "/m/1/user/friendsDetails"

        val (user1965, friends) = setupSomeUsers()
        inject[FakeUserActionsHelper].setUser(user1965)
        val mobileController = inject[MobileUserController]
        val result = mobileController.friends(0, 1000)(FakeRequest())
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse(
          """{"friends":[
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a673","firstName":"Paul","lastName":"Dirac","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false, "active":true},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a674","firstName":"James","lastName":"Chadwick","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false, "active":true},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a675","firstName":"Arthur","lastName":"Compton","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false, "active":true},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a676","firstName":"Albert","lastName":"Einstein","pictureName":"0.jpg","username":"test","searchFriend":true,"unfriended":false, "active":true}
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
        val route = com.keepit.controllers.mobile.routes.MobileUserController.socialNetworkInfo().toString
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
  }

}

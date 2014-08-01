package com.keepit.controllers.mobile

import org.specs2.mutable.Specification

import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }

import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.social.{ SocialNetworks, SocialId }
import SocialNetworks._
import securesocial.core._
import play.api.Play
import securesocial.core.providers.utils.{ PasswordHasher, BCryptPasswordHasher }
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.net.FakeHttpClientModule
import play.api.libs.json.JsArray
import securesocial.core.IdentityId
import com.keepit.model.UserConnection
import scala.Some
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.scraper.{ TestScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.common.external.FakeExternalServiceModule

class MobileUserControllerTest extends Specification with ShoeboxApplicationInjector {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    TestSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeSocialGraphModule(),
    TestABookServiceClientModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule()
  )

  def setupSomeUsers()(implicit injector: Injector) = {
    inject[Database].readWrite { implicit s =>
      val user1965 = userRepo.save(User(firstName = "Richard", lastName = "Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672")))
      val user1933 = userRepo.save(User(firstName = "Paul", lastName = "Dirac", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a673")))
      val user1935 = userRepo.save(User(firstName = "James", lastName = "Chadwick", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a674")))
      val user1927 = userRepo.save(User(firstName = "Arthur", lastName = "Compton", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a675")))
      val user1921 = userRepo.save(User(firstName = "Albert", lastName = "Einstein", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a676")))
      val friends = List(user1933, user1935, user1927, user1921)

      friends.foreach { friend => userConnRepo.save(UserConnection(user1 = user1965.id.get, user2 = friend.id.get)) }
      (user1965, friends)
    }
  }

  "mobileController" should {

    "get currentUser" in {
      running(new ShoeboxApplication(mobileControllerTestModules: _*)) {
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Shanee", lastName = "Smith"))
        }

        val path = com.keepit.controllers.mobile.routes.MobileUserController.currentUser().toString
        path === "/m/1/user/me"

        val controller = inject[MobileUserController]
        inject[FakeActionAuthenticator].setUser(user, Set(ExperimentType.ADMIN))

        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Shanee",
              "lastName":"Smith",
              "pictureName":"0.jpg",
              "emails":[],
              "notAuthed":[],
              "experiments":["admin"],
              "clickCount":0,
              "rekeepCount":0,
              "rekeepTotalCount":0
            }
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "return connected users from the database" in {
      running(new ShoeboxApplication(mobileControllerTestModules: _*)) {
        val route = com.keepit.controllers.mobile.routes.MobileUserController.friends().toString
        route === "/m/1/user/friendsDetails"

        val (user1965, friends) = setupSomeUsers()
        inject[FakeActionAuthenticator].setUser(user1965)
        val mobileController = inject[MobileUserController]
        val result = mobileController.friends(0, 1000)(FakeRequest())
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse(
          """{"friends":[
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a673","firstName":"Paul","lastName":"Dirac","pictureName":"0.jpg","searchFriend":true,"unfriended":false},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a674","firstName":"James","lastName":"Chadwick","pictureName":"0.jpg","searchFriend":true,"unfriended":false},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a675","firstName":"Arthur","lastName":"Compton","pictureName":"0.jpg","searchFriend":true,"unfriended":false},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a676","firstName":"Albert","lastName":"Einstein","pictureName":"0.jpg","searchFriend":true,"unfriended":false}
            ],
            "total":4}""")
        val resString = contentAsString(result)
        println(resString)
        val res = Json.parse(resString)
        res must equalTo(expected)
      }
    }

    "get socialNetworkInfo" in {
      running(new ShoeboxApplication(mobileControllerTestModules: _*)) {
        val route = com.keepit.controllers.mobile.routes.MobileUserController.socialNetworkInfo().toString
        route === "/m/1/user/networks"
        inject[Database].readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Richard", lastName = "Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672")))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("FRF"), networkType = SocialNetworks.FACEBOOK))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("LRF"), networkType = SocialNetworks.LINKEDIN,
            profileUrl = Some("http://www.linkedin.com/in/rf"), pictureUrl = Some("http://my.pic.com/pic.jpg")))
          inject[FakeActionAuthenticator].setUser(user)
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

    "change user password" in {
      running(new ShoeboxApplication(mobileControllerTestModules: _*)) {
        val bcrypt = Registry.hashers.get(PasswordHasher.BCryptHasher) getOrElse (new BCryptPasswordHasher(Play.current))
        val changePwdRoute = com.keepit.controllers.mobile.routes.MobileUserController.changePassword().toString
        changePwdRoute === "/m/1/password/change"
        inject[Database].readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Richard", lastName = "Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672")))
          val identityId = IdentityId("me@feynman.com", "userpass")
          val pInfo = bcrypt.hash("welcome")
          val socialUser = new SocialUser(identityId, "Richard", "Feynman", "Richard Feynman", Some(identityId.userId), None, AuthenticationMethod.UserPassword, passwordInfo = Some(pInfo))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("me@feynman.com"), networkType = FORTYTWO, credentials = Some(socialUser)))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("FRF"), networkType = FACEBOOK))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("LRF"), networkType = LINKEDIN,
            profileUrl = Some("http://www.linkedin.com/in/rf"), pictureUrl = Some("http://my.pic.com/pic.jpg")))
          inject[FakeActionAuthenticator].setUser(user)
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

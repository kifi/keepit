package com.keepit.controllers.mobile

import org.specs2.mutable.Specification

import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.search.{TestSearchServiceClientModule, Lang}
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector}

import play.api.libs.json.{Json, JsNumber, JsArray}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.social.{SocialNetworkType, SocialId, SocialNetworks}
import com.keepit.abook.TestABookServiceClientModule

class MobileUserControllerTest extends Specification with ShoeboxApplicationInjector {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    TestAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    TestSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeActionAuthenticatorModule(),
    TestABookServiceClientModule()
  )

  def setupSomeUsers()(implicit injector: Injector) = {
    inject[Database].readWrite {implicit s =>
      val user1965 = userRepo.save(User(firstName="Richard",lastName="Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672")))
      val user1933 = userRepo.save(User(firstName="Paul",lastName="Dirac", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a673")))
      val user1935 = userRepo.save(User(firstName="James",lastName="Chadwick", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a674")))
      val user1927 = userRepo.save(User(firstName="Arthur",lastName="Compton", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a675")))
      val user1921 = userRepo.save(User(firstName="Albert",lastName="Einstein", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a676")))
      val friends = List(user1933,user1935,user1927,user1921)

      friends.foreach {friend => userConnRepo.save(UserConnection(user1=user1965.id.get,user2=friend.id.get))}
      (user1965,friends)
    }
  }

  "mobileController" should {

    "get currentUser" in {
      running(new ShoeboxApplication(mobileControllerTestModules:_*)) {
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Shanee", lastName = "Smith"))
        }

        val path = com.keepit.controllers.mobile.routes.MobileUserController.currentUser().toString
        path === "/m/1/user/me"

        val controller = inject[MobileUserController]
        inject[FakeActionAuthenticator].setUser(user, Set(ExperimentType.ADMIN))

        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Shanee",
              "lastName":"Smith",
              "pictureName":"0.jpg",
              "description":"",
              "emails":[],
              "experiments":["admin"]}
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "return connected users from the database" in {
      running(new ShoeboxApplication(mobileControllerTestModules:_*)) {
        val route = com.keepit.controllers.mobile.routes.MobileUserController.getFriends().toString
        route === "/m/1/user/friends"

        val (user1965, friends) = setupSomeUsers()
        inject[FakeActionAuthenticator].setUser(user1965)
        val mobileController = inject[MobileUserController]
        val result = mobileController.getFriends()(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        val expected = Json.parse("""[
            {"id":"742fa97c-c12a-4dcf-bff5-0f33280ef35a","firstName":"Noah, Kifi Help","lastName":"","pictureName":"Vjy5S.jpg"},
            {"id":"742fa97c-c12a-4dcf-bff5-0f33280ef35a","firstName":"Tamila, Kifi Help","lastName":"","pictureName":"tmilz.jpg"},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a673","firstName":"Paul","lastName":"Dirac","pictureName":"0.jpg"},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a674","firstName":"James","lastName":"Chadwick","pictureName":"0.jpg"},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a675","firstName":"Arthur","lastName":"Compton","pictureName":"0.jpg"},
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a676","firstName":"Albert","lastName":"Einstein","pictureName":"0.jpg"}
          ]""")
        Json.parse(contentAsString(result)).as[JsArray].value.toSet must equalTo(expected.as[JsArray].value.toSet)
      }
    }

    "get socialNetworkInfo" in {
      running(new ShoeboxApplication(mobileControllerTestModules:_*)) {
        val route = com.keepit.controllers.mobile.routes.MobileUserController.socialNetworkInfo().toString
        route === "/m/1/user/networks"
        inject[Database].readWrite {implicit s =>
          val user = userRepo.save(User(firstName="Richard", lastName="Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672")))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("FRF"), networkType = SocialNetworks.FACEBOOK))
          socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Richard Feynman", state = SocialUserInfoStates.CREATED, socialId = SocialId("LRF"), networkType = SocialNetworks.LINKEDIN,
              profileUrl = Some("http://www.linkedin.com/in/rf"), pictureUrl = Some("http://my.pic.com/pic.jpg")))
          inject[FakeActionAuthenticator].setUser(user)
        }
        val mobileController = inject[MobileUserController]
        val result = mobileController.socialNetworkInfo()(FakeRequest())
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");
        val expected = Json.parse("""[
            {"network":"facebook","profileUrl":"http://facebook.com/FRF","pictureUrl":"http://graph.facebook.com/FRF/picture?width=50&height=50"},
            {"network":"linkedin","profileUrl":"http://www.linkedin.com/in/rf","pictureUrl":"http://my.pic.com/pic.jpg"}
          ]""")
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }
  }
}

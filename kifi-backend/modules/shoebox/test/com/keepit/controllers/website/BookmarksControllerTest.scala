package com.keepit.controllers.website

import org.specs2.mutable.Specification

import net.codingwell.scalaguice.ScalaModule

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller.FortyTwoCookies.{KifiInstallationCookie, ImpersonateCookie}
import com.keepit.common.controller.{ActionAuthenticator, ShoeboxActionAuthenticator}
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.{FakeHttpClient, HttpClient}
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.social.{SecureSocialUserPlugin, SecureSocialAuthenticatorPlugin, SocialId, SocialNetworks}
import com.keepit.test.ShoeboxApplication

import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import securesocial.core.providers.Token
import com.keepit.abook.TestABookServiceClientModule

class BookmarksControllerTest extends Specification with ApplicationInjector {

  private val creds = SocialUser(IdentityId("asdf", "facebook"),
    "Eishay", "Smith", "Eishay Smith", None, None, AuthenticationMethod.OAuth2, None, None)

  private def setup() = {
    inject[Database].readWrite { implicit session =>
      val userRepo = inject[UserRepo]
      val socialConnectionRepo = inject[SocialConnectionRepo]
      val socialuserInfoRepo = inject[SocialUserInfoRepo]

      val user1 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
      val user2 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

      val su1 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("asdf"),
        networkType = SocialNetworks.FACEBOOK, userId = user1.id, credentials = Some(creds)))
      val su2 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("aoeu"),
        networkType = SocialNetworks.LINKEDIN, userId = user1.id))
      val su3 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Léo Grimaldi", socialId = SocialId("arst"),
        networkType = SocialNetworks.FACEBOOK, userId = None))
      val su4 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Andrew Conner", socialId = SocialId("abcd"),
        networkType = SocialNetworks.LINKEDIN, userId = user2.id))
      val su5 = socialuserInfoRepo.save(SocialUserInfo(fullName = "杨莹", socialId = SocialId("defg"),
        networkType = SocialNetworks.LINKEDIN, userId = user2.id))


      socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su3.id.get))
      socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su4.id.get))
      socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su5.id.get))

      (su1, user1)
    }
  }

  "BookmarksController" should {
    "keepMultiple" in new WithUserController {
      val (su1, user1) = setup()
      val withoutCollection =
        KeepInfo(id = ExternalId[Bookmark](), title = Some("title 1"), url = "http://www.hi.com1", false) ::
        KeepInfo(id = ExternalId[Bookmark](), title = Some("title 2"), url = "http://www.hi.com2", true) ::
        KeepInfo(id = ExternalId[Bookmark](), title = Some("title 3"), url = "http://www.hi.com3", false) ::
        Nil
      val withoutCollection =
        KeepInfo(id = ExternalId[Bookmark](), title = Some("title 11"), url = "http://www.hi.com11", false) ::
        KeepInfo(id = ExternalId[Bookmark](), title = Some("title 21"), url = "http://www.hi.com21", true) ::
        KeepInfo(id = ExternalId[Bookmark](), title = Some("title 31"), url = "http://www.hi.com31", false) ::
        Nil
      val keepsAndCollections =
        KeepInfosWithCollection(None, withoutCollection) ::
        KeepInfosWithCollection(Some(Right(ExternalId[Collection]())), witCollection) ::
        Nil

      val route = com.keepit.controllers.website.routes.BookmarksController.keepMultiple().toString
      route === "/site/keeps/add"

      val json = Json.toJson(keepsAndCollections)
      json.toString === "foo"
      val request = FakeRequest("POST", path).withJsonBody(json)
      val result = route(request).get

      inject[FakeActionAuthenticator].setUser(user1)
      val controller = inject[BookmarksController]
      val result = controller.keepMultiple()(FakeRequest())
      status(result) must equalTo(OK);
      contentType(result) must beSome("application/json");
      val expected = Json.parse("""[
          {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a673","firstName":"Paul","lastName":"Dirac","pictureName":"0.jpg"},
          {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a674","firstName":"James","lastName":"Chadwick","pictureName":"0.jpg"},
          {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a675","firstName":"Arthur","lastName":"Compton","pictureName":"0.jpg"},
          {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a676","firstName":"Albert","lastName":"Einstein","pictureName":"0.jpg"}
        ]""")
      Json.parse(contentAsString(result)) must equalTo(expected)

    }
  }
}

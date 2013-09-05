package com.keepit.controllers.website

import org.specs2.mutable.Specification

import net.codingwell.scalaguice.ScalaModule

import com.keepit.common.controller.FortyTwoCookies.{KifiInstallationCookie, ImpersonateCookie}
import com.keepit.common.controller.{ActionAuthenticator, ShoeboxActionAuthenticator}
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.{FakeHttpClient, HttpClient}
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.realtime.UrbanAirshipConfig
import com.keepit.social.{SecureSocialUserPlugin, SecureSocialAuthenticatorPlugin, SocialId, SocialNetworks}
import com.keepit.test.ShoeboxApplication

import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import securesocial.core.providers.Token

class UserControllerTest extends Specification with ApplicationInjector {

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

  private class WithUserController extends WithApplication(new ShoeboxApplication(new ScalaModule {
    def configure() {
      install(FakeMailModule())
      bind[ActionAuthenticator].to[ShoeboxActionAuthenticator]
      bind[HttpClient].toInstance(new FakeHttpClient())
      bind[ImpersonateCookie].toInstance(new ImpersonateCookie(Some("dev.ezkeep.com")))
      bind[UrbanAirshipConfig].toInstance(UrbanAirshipConfig("test", "test"))
      bind[KifiInstallationCookie].toInstance(new KifiInstallationCookie(Some("dev.ezkeep.com")))
      bind[SecureSocialAuthenticatorPlugin].toInstance(new SecureSocialAuthenticatorPlugin {
        def delete(id: String) = Right(())
        def save(authenticator: Authenticator) = Right(())
        def find(id: String) = Authenticator.create(creds) match {
          case Right(auth) => Right(Some(auth))
          case Left(error) => Left(error)
        }
      })
      bind[SecureSocialUserPlugin].toInstance(new SecureSocialUserPlugin {
        def findByEmailAndProvider(email: String, providerId: String) = ???
        def deleteToken(uuid: String) {}
        def save(token: Token) {}
        def save(identity: Identity) = ???
        def deleteExpiredTokens() {}
        def findToken(token: String) = ???
        def find(id: IdentityId) = Some(creds)
      })
    }
  })) {
    lazy val (sui1, user1) = setup()
    lazy val cookie = Authenticator.create(sui1.credentials.get).right.get.toCookie
    lazy val controller = inject[UserController]
  }

  "UserController" should {
    "fetch my social connections, in the proper order" in new WithUserController {
      def getNames(result: Result): Seq[String] = {
        Json.fromJson[Seq[JsObject]](Json.parse(contentAsString(result))).get.map(j => (j \ "label").as[String])
      }

      val res1 = controller.getAllConnections(Some("leo"), None, None, 10)(FakeRequest("GET", "/").withCookies(cookie))
      status(res1) must_== OK
      getNames(res1) must_== Seq("Léo Grimaldi")

      val res2 = controller.getAllConnections(Some("莹"), None, None, 10)(FakeRequest("GET", "/").withCookies(cookie))
      status(res2) must_== OK
      getNames(res2) must_== Seq("杨莹")

      val res3 = controller.getAllConnections(None, None, None, 2)(FakeRequest("GET", "/").withCookies(cookie))
      status(res3) must_== OK
      getNames(res3) must_== Seq("Andrew Conner", "Léo Grimaldi")

      val res4 = controller.getAllConnections(Some("leo"), Some("facebook"), None, 2)(FakeRequest("GET", "/").withCookies(cookie))
      status(res4) must_== OK
      getNames(res4) must_== Seq("Léo Grimaldi")

      val res5 = controller.getAllConnections(None, None, Some("facebook/arst"), 2)(FakeRequest("GET", "/").withCookies(cookie))
      status(res5) must_== OK
      getNames(res5) must_== Seq("杨莹")
    }
  }
}

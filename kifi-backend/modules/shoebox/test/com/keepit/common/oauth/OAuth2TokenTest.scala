package com.keepit.common.oauth

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.auth.AuthException
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ KifiSession, FakeUserActionsModule }
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.core.AuthController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, KeepImportsModule }
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._
import securesocial.core.{ AuthenticationMethod, IdentityId, OAuth2Info, SocialUser }
import KifiSession._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

import scala.concurrent.Future

class OAuth2TokenTest extends Specification with ShoeboxApplicationInjector {

  implicit val context = HeimdalContext.empty

  val oauth2Info = OAuth2Info("asdf-token", None, None, None)

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit s =>
      val identityId = IdentityId("100004067535411", "facebook")
      val email = "bar@foo.com"
      val socialUser = SocialUser(identityId, "Foo", "Bar", "Foo Bar",
        Some(email), Some("http://www.foo.com/bar"), AuthenticationMethod.OAuth2, None,
        Some(oauth2Info), None)

      val user = UserFactory.user().withName("Foo", "Bar").withUsername("foo-bar").saved
      val userEmail = userEmailAddressRepo.save(UserEmailAddress(userId = user.id.get, state = UserEmailAddressStates.VERIFIED, address = EmailAddress(email)))
      val sui = socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Foo Bar", state = SocialUserInfoStates.CREATED, socialId = SocialId(identityId.userId), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)))
      (socialUser, user, sui)
    }
  }

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeShoeboxAppSecureSocialModule(),
    FakeUserActionsModule(),
    FakeCortexServiceClientModule(),
    KeepImportsModule(),
    FakeCuratorServiceClientModule(),
    FakeOAuth1ConfigurationModule(),
    FakeOAuth2ConfigurationModule()
  )

  "AuthController" should {
    "(signup) handle successful token signup" in {
      running(new ShoeboxApplication(modules: _*)) {
        val oauth2TokenInfo = OAuth2TokenInfo.fromOAuth2Info(oauth2Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.accessTokenSignup("facebook").toString()
        path === "/auth/token-signup/facebook"

        val payload = Json.toJson(oauth2TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.accessTokenSignup("facebook")(request)
        status(result) === OK

        val sess = session(result)
        sess.getUserId.isDefined === false // no user (yet)

        val json = contentAsJson(result)
        (json \ "code").as[String] === "continue_signup" // success!
        val sessionId = (json \ "sessionId").as[String] // sessionId should be set
        ExternalId.UUIDPattern.pattern.matcher(sessionId).matches() === true

        val profile = inject[FakeFacebookOAuthProvider].profileInfo
        val suiOpt = db.readOnlyMaster { implicit ro => socialUserInfoRepo.getOpt(SocialId(profile.userId.id), SocialNetworks.FACEBOOK) }
        suiOpt.isDefined === true
        suiOpt.exists { sui => sui.userId.isEmpty } === true // userId must not be set in this case
        suiOpt.get.credentials.isDefined === true
        val socialUser = suiOpt.get.credentials.get
        socialUser.oAuth2Info.isDefined === true
      }
    }
    "(signup) report invalid token when fb reports error" in {
      running(new ShoeboxApplication(modules: _*)) {
        val fakeProvider = inject[FakeFacebookOAuthProvider]
        fakeProvider.setProfileInfoF(() => Future.failed(new AuthException("invalid token"))) // facebook reports errors

        val oauth2TokenInfo = OAuth2TokenInfo.fromOAuth2Info(oauth2Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.accessTokenSignup("facebook").toString()
        path === "/auth/token-signup/facebook"

        val payload = Json.toJson(oauth2TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.accessTokenSignup("facebook")(request)
        status(result) === BAD_REQUEST

        val sess = session(result)
        sess.getUserId.isDefined === false

        val json = contentAsJson(result)
        (json \ "error").as[String] === "invalid_token" // success!
        (json \ "sessionId").asOpt[String].isDefined === false // sessionId shouldn't be set

        val profile = inject[FakeFacebookOAuthProvider].profileInfo
        val suiOpt = db.readOnlyMaster { implicit ro => socialUserInfoRepo.getOpt(SocialId(profile.userId.id), SocialNetworks.FACEBOOK) }
        suiOpt.isDefined === false
      }
    }
    "(signup) present connect option when user tries to sign-up with social when email already registered" in {
      running(new ShoeboxApplication(modules: _*)) {
        setup()
        val fakeProvider = inject[FakeFacebookOAuthProvider]
        val lnkdInfo = fakeProvider.profileInfo.copy(providerId = ProviderIds.LinkedIn, userId = ProviderUserId("1001"))
        fakeProvider.setProfileInfo(lnkdInfo)

        val oauth2TokenInfo = OAuth2TokenInfo.fromOAuth2Info(oauth2Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.accessTokenSignup("linkedin").toString()
        path === "/auth/token-signup/linkedin"

        val payload = Json.toJson(oauth2TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.accessTokenSignup("facebook")(request)
        status(result) === OK

        val sess = session(result)
        sess.getUserId.isDefined === false

        val json = contentAsJson(result)
        (json \ "code").as[String] === "connect_option" // success!
        (json \ "sessionId").asOpt[String].isDefined === true // sessionId is set

        val suiOpt = db.readOnlyMaster { implicit ro =>
          socialUserInfoRepo.getOpt(SocialId(lnkdInfo.userId.id), SocialNetworks.LINKEDIN)
        }
        suiOpt.isDefined === true
        suiOpt.exists { sui => sui.userId.isEmpty } === true // userId must not be set in this case
      }
    }
    "(login) handle successful token login" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (socialUser, user, sui) = setup()
        val fakeProvider = inject[FakeFacebookOAuthProvider]
        fakeProvider.setProfileInfoF(() => Future.successful(socialUser))

        val oauth2TokenInfo = OAuth2TokenInfo.fromOAuth2Info(socialUser.oAuth2Info.get)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.accessTokenLogin("facebook").toString()
        path === "/auth/token-login/facebook"

        val payload = Json.toJson(oauth2TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.accessTokenLogin("facebook")(request)
        status(result) === OK

        val sess = session(result)
        sess.getUserId.isDefined === true

        val json = contentAsJson(result)
        (json \ "code").as[String] === "user_logged_in" // success!
        val sessionId = (json \ "sessionId").as[String] // sessionId should be set
        ExternalId.UUIDPattern.pattern.matcher(sessionId).matches() === true

        val suiOpt = db.readOnlyMaster { implicit ro => socialUserInfoRepo.getOpt(SocialId(socialUser.identityId.userId), SocialNetworks.FACEBOOK) }
        suiOpt.isDefined === true
        val userIdOpt = for {
          sui <- suiOpt
          userId <- sui.userId
        } yield {
          userId
        }
        userIdOpt.exists(_ == sess.getUserId.get) === true
      }
    }
    "(login) report user not found when not registered" in {
      running(new ShoeboxApplication(modules: _*)) {
        // no need for setup

        val oauth2TokenInfo = OAuth2TokenInfo.fromOAuth2Info(oauth2Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.accessTokenLogin("facebook").toString()
        path === "/auth/token-login/facebook"

        val payload = Json.toJson(oauth2TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.accessTokenLogin("facebook")(request)
        status(result) === NOT_FOUND

        val sess = session(result)
        sess.getUserId.isDefined === false

        val json = contentAsJson(result)
        (json \ "error").as[String] === "user_not_found" // success!
        (json \ "sessionId").asOpt[String].isDefined === false // sessionId shouldn't be set
      }
    }
    "(login) report invalid token when fb reports error" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (socialUser, _, _) = setup()
        val fakeProvider = inject[FakeFacebookOAuthProvider]
        fakeProvider.setProfileInfoF(() => Future.failed(new AuthException("invalid token"))) // facebook reports errors

        val oauth2TokenInfo = OAuth2TokenInfo.fromOAuth2Info(socialUser.oAuth2Info.get)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.accessTokenLogin("facebook").toString()
        path === "/auth/token-login/facebook"

        val payload = Json.toJson(oauth2TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.accessTokenLogin("facebook")(request)
        status(result) === BAD_REQUEST

        val sess = session(result)
        sess.getUserId.isDefined === false

        val json = contentAsJson(result)
        (json \ "error").as[String] === "invalid_token" // success!
        (json \ "sessionId").asOpt[String].isDefined === false // sessionId shouldn't be set
      }
    }
  }

}

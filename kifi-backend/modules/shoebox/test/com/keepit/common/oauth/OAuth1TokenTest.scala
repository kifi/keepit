package com.keepit.common.oauth

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.auth.AuthException
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ FakeUserActionsModule, KifiSession }
import KifiSession._
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.core.AuthController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ KeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.social._
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core.{ IdentityId, SocialUser, AuthenticationMethod, OAuth1Info }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

import scala.concurrent.Future

class OAuth1TokenTest extends Specification with ShoeboxApplicationInjector {

  implicit val context = HeimdalContext.empty

  val oauth1Info = OAuth1Info("twitter-oauth1-token", "foobar")
  val twitterIdentity = {
    val identityId = IdentityId("2906435114", "twitter")
    val profileInfo = TwitterUserInfo(
      id = identityId.userId.toLong,
      screenName = "woof",
      name = "woof",
      defaultProfile = false,
      defaultProfileImage = false,
      profileImageUrl = "http://www.woof.com/woof.jpg",
      followersCount = 42,
      friendsCount = 42
    )
    TwitterOAuthProvider.toIdentity(oauth1Info, profileInfo)
  }

  def setup()(implicit injector: Injector) = {
    val socialUser = UserIdentity(twitterIdentity)
    db.readWrite { implicit s =>
      val user = UserFactory.user().withName("", "").withUsername("woof").saved
      val sui = socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Woof", state = SocialUserInfoStates.CREATED, socialId = SocialId(socialUser.identityId.userId), networkType = SocialNetworks.TWITTER, credentials = Some(socialUser)))
      (user, sui)
    }
  }

  def getSocialUserInfo(identity: RichIdentity)(implicit injector: Injector): Option[SocialUserInfo] = {
    val identityId = RichIdentity.toIdentityId(identity)
    val socialId = IdentityHelpers.parseSocialId(identityId)
    val networkType = IdentityHelpers.parseNetworkType(identityId)
    db.readOnlyMaster { implicit session =>
      socialUserInfoRepo.getOpt(socialId, networkType)
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
    FakeOAuthConfigurationModule()
  )

  "AuthController (oauth1)" should {
    "(signup) handle successful token signup" in {
      running(new ShoeboxApplication(modules: _*)) {
        inject[FakeTwitterOAuthProvider].setIdentity(Future.successful(twitterIdentity))
        val oauth1TokenInfo = OAuth1TokenInfo.fromOAuth1Info(oauth1Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.oauth1TokenSignup("twitter").toString()
        path === "/auth/oauth1-signup/twitter"

        val payload = Json.toJson(oauth1TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.oauth1TokenSignup("twitter")(request)
        status(result) === OK

        val sess = session(result)
        sess.getUserId.isDefined === false // no user (yet)

        val json = contentAsJson(result)
        (json \ "code").as[String] === "continue_signup" // success!
        val sessionId = (json \ "sessionId").as[String] // sessionId should be set
        ExternalId.UUIDPattern.pattern.matcher(sessionId).matches() === true

        val suiOpt = getSocialUserInfo(twitterIdentity)
        suiOpt.isDefined === true
        suiOpt.exists { sui => sui.userId.isEmpty } === true // userId must not be set in this case

        val socialUser = suiOpt.get.credentials.get
        socialUser.oAuth1Info.isDefined === true
      }
    }
    "(signup) report invalid token when twtr reports error" in {
      running(new ShoeboxApplication(modules: _*)) {
        val fakeProvider = inject[FakeTwitterOAuthProvider]
        fakeProvider.setIdentity(Future.failed(new AuthException("invalid token"))) // twitter reports errors

        val oauth1TokenInfo = OAuth1TokenInfo.fromOAuth1Info(oauth1Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.oauth1TokenSignup("twitter").toString()
        path === "/auth/oauth1-signup/twitter"

        val payload = Json.toJson(oauth1TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.oauth1TokenSignup("twitter")(request)
        status(result) === BAD_REQUEST

        val sess = session(result)
        sess.getUserId.isDefined === false

        val json = contentAsJson(result)
        (json \ "error").as[String] === "invalid_token" // success!
        (json \ "sessionId").asOpt[String].isDefined === false // sessionId shouldn't be set

        val suiOpt = getSocialUserInfo(twitterIdentity)
        suiOpt.isDefined === false
      }
    }
    "(login) handle successful token login" in {
      running(new ShoeboxApplication(modules: _*)) {
        setup()
        val fakeProvider = inject[FakeTwitterOAuthProvider]
        fakeProvider.setIdentity(Future.successful(twitterIdentity))

        val oauth1TokenInfo = OAuth1TokenInfo.fromOAuth1Info(oauth1Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.oauth1TokenLogin("twitter").toString()
        path === "/auth/oauth1-login/twitter"

        val payload = Json.toJson(oauth1TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.oauth1TokenLogin("twitter")(request)
        status(result) === OK

        val sess = session(result)
        sess.getUserId.isDefined === true

        val json = contentAsJson(result)
        (json \ "code").as[String] === "user_logged_in" // success!
        val sessionId = (json \ "sessionId").as[String] // sessionId should be set
        ExternalId.UUIDPattern.pattern.matcher(sessionId).matches() === true

        val suiOpt = getSocialUserInfo(twitterIdentity)
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
        inject[FakeTwitterOAuthProvider].setIdentity(Future.successful(twitterIdentity))
        val oauth1TokenInfo = OAuth1TokenInfo.fromOAuth1Info(oauth1Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.oauth1TokenLogin("twitter").toString()
        path === "/auth/oauth1-login/twitter"

        val payload = Json.toJson(oauth1TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.oauth1TokenLogin("twitter")(request)
        status(result) === NOT_FOUND

        val sess = session(result)
        sess.getUserId.isDefined === false

        val json = contentAsJson(result)
        (json \ "error").as[String] === "user_not_found" // success!
        (json \ "sessionId").asOpt[String].isDefined === false // sessionId shouldn't be set
      }
    }

    "(login) report invalid token when twtr reports error" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (_, _) = setup()
        val fakeProvider = inject[FakeTwitterOAuthProvider]
        fakeProvider.setIdentity(Future.failed(new AuthException("invalid token"))) // twitter reports errors

        val oauth1TokenInfo = OAuth1TokenInfo.fromOAuth1Info(oauth1Info)
        val authController = inject[AuthController]
        val path = com.keepit.controllers.core.routes.AuthController.oauth1TokenLogin("twitter").toString()
        path === "/auth/oauth1-login/twitter"

        val payload = Json.toJson(oauth1TokenInfo)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.accessTokenLogin("twitter")(request)
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

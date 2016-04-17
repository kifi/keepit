package com.keepit.commanders

import com.keepit.common.oauth.SlackIdentity
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ KifiSession, MaybeAppFakeUserActionsModule }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ KeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.slack.SlackIdentityCommander
import com.keepit.slack.models._
import com.keepit.social.{ IdentityHelpers, SocialNetworks, SocialId }
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._
import KifiSession._

class AuthCommanderTest extends Specification with ShoeboxApplicationInjector {

  implicit val context = HeimdalContext.empty

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
    MaybeAppFakeUserActionsModule(),
    FakeCortexServiceClientModule(),
    KeepImportsModule()
  )

  "AuthCommander" should {
    "login via social" in {
      running(new ShoeboxApplication(modules: _*)) {

        val userRepo = inject[UserRepo]
        val suiRepo = inject[SocialUserInfoRepo]

        // create sui
        val identityId = IdentityId("asdf", "facebook")
        val oAuth2Info = OAuth2Info("fake_token")
        val socialUser = SocialUser(identityId, "Foo", "Bar", "Foo Bar", Some("foo@bar.com"), None, AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
        val sui = db.readWrite { implicit rw =>
          suiRepo.save(SocialUserInfo(fullName = "Foo Bar", socialId = SocialId(identityId.userId), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)))
        }

        val authCommander = inject[AuthCommander]
        val path = "/auth/token-login/facebook"
        implicit val request = FakeRequest("POST", path)

        // kifi user not created yet
        val res = authCommander.loginWithTrustedSocialIdentity(identityId)
        res.header.status === NOT_FOUND
        res.session.getUserId.isDefined === false

        // create user
        val (user, suiWithUser) = db.readWrite { implicit rw =>
          val user = UserFactory.user().saved
          val suiWithUser = suiRepo.save(sui.copy(userId = user.id))
          (user, suiWithUser)
        }
        val res1 = authCommander.loginWithTrustedSocialIdentity(identityId)
        res1.header.status === OK
        res1.session.getUserId.isDefined === true
        res1.session.getUserId.get === user.id.get
      }
    }
  }

  "login via Slack" in {
    running(new ShoeboxApplication(modules: _*)) {

      val user = db.readWrite { implicit rw => UserFactory.user().saved }
      val slackTeamId = SlackTeamId("T12")
      val slackUserId = SlackUserId("U42")
      val identityId = IdentityHelpers.toIdentityId(slackTeamId, slackUserId)

      val authCommander = inject[AuthCommander]
      implicit val request = FakeRequest("POST", "/")

      // No SlackTeamMembership yet
      val res = authCommander.loginWithTrustedSocialIdentity(identityId)
      res.header.status === NOT_FOUND
      res.session.getUserId.isDefined === false

      // Register Slack Identity
      db.readWrite { implicit session =>
        slackCommander.internSlackIdentity(
          Some(user.id.get),
          SlackIdentity(
            slackTeamId,
            slackUserId,
            Some(BasicSlackTeamInfo(slackTeamId, SlackTeamName("fake"))),
            None,
            Some(SlackTokenWithScopes(SlackUserAccessToken("fake"), Set.empty))
          )
        )
      }

      val res1 = authCommander.loginWithTrustedSocialIdentity(identityId)
      res1.header.status === OK
      res1.session.getUserId.isDefined === true
      res1.session.getUserId.get === user.id.get
    }
  }
}

package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.crypto.FakeCryptoModule

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.oauth.ProviderIds
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.mindrot.jbcrypt.BCrypt
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import com.keepit.commanders.{ FullSocialId, InviteCommander }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class InviteControllerTest extends Specification with ShoeboxApplicationInjector {

  args(skipAll = true)

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeUserActionsModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCortexServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule()
  )

  val senderEmail = EmailAddress("foo@foo.com")
  val recipientEmail = EmailAddress("bar@foo.com")

  def setUp()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val user1 = UserFactory.user().withName("Foo", "Foo").withUsername("test").saved
      val email1 = userEmailAddressCommander.intern(userId = user1.id.get, address = senderEmail).get._1
      val pwdInfo = PasswordInfo("bcrypt", BCrypt.hashpw("random_pwd", BCrypt.gensalt()))
      val uc1 = userCredRepo.save(UserCred(userId = user1.id.get, credentials = pwdInfo.password))
      val socialUserInfoRepo = inject[SocialUserInfoRepo]
      val socialUser = SocialUser(
        identityId = IdentityId(email1.address.address, "userpass"),
        firstName = user1.firstName,
        lastName = user1.lastName,
        fullName = user1.fullName,
        email = Some(email1.address.address),
        avatarUrl = None,
        authMethod = AuthenticationMethod.UserPassword,
        passwordInfo = Some(pwdInfo)
      )
      val sui1 = socialUserInfoRepo.save(SocialUserInfo(userId = user1.id, fullName = user1.fullName, socialId = SocialId(email1.address.address), networkType = SocialNetworks.FORTYTWO, credentials = Some(socialUser)))
      val inviteRepo = inject[InvitationRepo]
      val invite1 = inviteRepo.save(Invitation(senderUserId = user1.id, recipientSocialUserId = None, recipientEmailAddress = Some(recipientEmail)))

      // twitter
      val oauth1Info = OAuth1Info("twitter-oauth1-token", "foobar-secret")
      val identityId2 = IdentityId("1234", ProviderIds.Twitter.id)
      val socialUser2 = SocialUser(identityId2, "Foo", "Bar", "Foo Bar", None, None, AuthenticationMethod.OAuth1, oAuth1Info = Some(oauth1Info))
      val sui2 =
        socialUserInfoRepo.save(SocialUserInfo(
          userId = user1.id,
          fullName = socialUser2.fullName,
          pictureUrl = Some("http://random/rand.png"),
          profileUrl = None, // not set
          socialId = SocialId(identityId2.userId),
          networkType = SocialNetworks.TWITTER,
          credentials = Some(socialUser2)
        ))
      val identityId3 = IdentityId("7890", ProviderIds.Twitter.id)
      val socialUser3 = SocialUser(identityId3, "FooTweet", "BarTweet", "FooTweet BarTweet", None, None, AuthenticationMethod.OAuth1, oAuth1Info = Some(oauth1Info))
      val sui3 =
        socialUserInfoRepo.save(SocialUserInfo(
          userId = None,
          fullName = socialUser3.fullName,
          pictureUrl = Some("http://random/rand.png"),
          profileUrl = None, // not set
          socialId = SocialId(identityId3.userId),
          networkType = SocialNetworks.TWITTER,
          credentials = Some(socialUser3)
        ))
      (user1, email1, sui1, invite1, sui2, sui3)
    }
  }

  "InviteController" should {

    "acceptInvite should be public endpoint" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user1, email1, sui1, invite1, _, _) = setUp()
        val inviteController = inject[InviteController]

        // ensure path exists
        val path = com.keepit.controllers.website.routes.InviteController.acceptInvite(invite1.externalId).url
        path === s"/invite/${invite1.externalId}"

        // do not set user
        val request = FakeRequest()
        val result = inviteController.acceptInvite(invite1.externalId)(request)
        val code = status(result)
        code !== FORBIDDEN

        // README: if you get a 404 here make sure you have pulled the marketing submodule modules/shoebox/marketing
        // 1) run from repository root: git submodule update --init
        // 2) cd to modules/shoebox/marketing and run: npm install
        // 3) run "mkt" in SBT from /kifi-backend/ to generate the file this test depends on (you should only have to do this once)
        code === OK

        // landing page at this point -- we'll make sure cookie is set
        val invCookie = cookies(result).get("inv")
        invCookie.isDefined === true
        invCookie.exists(_.value == invite1.externalId.id) === true

        // Facebook OpenGraph Tags should be set
        val commander = inject[InviteCommander]
        val html = contentAsString(result)
        html must contain("og:title")
        html must contain(commander.fbTitle(Some(user1.firstName)))
        html must contain("og:description")
        html must contain(commander.fbDescription)
        html must contain("og:url")
        html must contain(commander.acceptUrl(invite1.externalId, false))

        // now set user (sanity check)
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest()
        val result1 = inviteController.acceptInvite(invite1.externalId)(request1)
        status(result1) === SEE_OTHER // redirect
      }
    }
  }

}

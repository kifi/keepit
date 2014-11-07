package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerConfigModule, FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.mindrot.jbcrypt.BCrypt
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import com.keepit.commanders.InviteCommander

class InviteControllerTest extends Specification with ShoeboxApplicationInjector {

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
    FakeScrapeSchedulerModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeScrapeSchedulerConfigModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule(),
    UrlPatternRuleModule(),
    FakeCuratorServiceClientModule()
  )

  val senderEmail = EmailAddress("foo@foo.com")
  val recipientEmail = EmailAddress("bar@foo.com")

  def setUp()(implicit injector: Injector) = {
    db.readWrite { implicit session =>
      val user1 = userRepo.save(User(firstName = "Foo", lastName = "Foo", username = Username("test"), normalizedUsername = "test"))
      val email1 = emailAddressRepo.save(UserEmailAddress(userId = user1.id.get, address = senderEmail))
      val pwdInfo = PasswordInfo("bcrypt", BCrypt.hashpw("random_pwd", BCrypt.gensalt()))
      val uc1 = userCredRepo.save(UserCred(userId = user1.id.get, loginName = email1.address.address, provider = "bcrypt", salt = pwdInfo.salt.getOrElse(""), credentials = pwdInfo.password))
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
      (user1, email1, sui1, invite1)
    }
  }

  "InviteController" should {
    "acceptInvite should be public endpoint" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user1, email1, sui1, invite1) = setUp()
        val inviteController = inject[InviteController]

        // ensure path exists
        val path = com.keepit.controllers.website.routes.InviteController.acceptInvite(invite1.externalId).url
        path === s"/invite/${invite1.externalId}"

        // do not set user
        val request = FakeRequest()
        val result = inviteController.acceptInvite(invite1.externalId)(request)
        val code = status(result)
        code !== FORBIDDEN
        code === 200
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
        html must contain(commander.acceptUrl(invite1.externalId))

        // now set user (sanity check)
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest()
        val result1 = inviteController.acceptInvite(invite1.externalId)(request1)
        status(result1) === SEE_OTHER // redirect
      }
    }
  }

}

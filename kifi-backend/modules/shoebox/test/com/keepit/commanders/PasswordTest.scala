package com.keepit.commanders

import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.controller.FakeActionAuthenticator
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ EmailAddress, TestMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeSocialGraphModule, TestShoeboxAppSecureSocialModule }
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.{ HeimdalContext, TestHeimdalServiceClientModule }
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, TestScraperServiceClientModule }
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, KeepImportsModule }
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector, ShoeboxInjectionHelpers }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._

class PasswordTest extends Specification with ShoeboxApplicationInjector with ShoeboxInjectionHelpers {

  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeShoeboxServiceModule(),
    TestSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    TestABookServiceClientModule(),
    TestMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    TestHeimdalServiceClientModule(),
    TestShoeboxAppSecureSocialModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule(),
    KeepImportsModule()
  )

  "PasswordHandler" should {
    "handle change password" in {
      running(new ShoeboxApplication(modules: _*)) {
        val oldPwd = "1234567"
        val newPwd = "7654321"
        val hasher = Registry.hashers.get("bcrypt").get
        val pwdInfo = hasher.hash(oldPwd)
        val user = db.readWrite { implicit session =>
          val user1 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
          val email1 = emailAddressRepo.save(UserEmailAddress(userId = user1.id.get, address = EmailAddress("username@42go.com")))
          val uc1 = userCredRepo.save(UserCred(userId = user1.id.get, loginName = email1.address.address, provider = "bcrypt", salt = pwdInfo.salt.getOrElse(""), credentials = pwdInfo.password))
          val socialUserRepo = inject[SocialUserInfoRepo]
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
          socialUserRepo.save(SocialUserInfo(userId = user1.id, fullName = user1.fullName, socialId = SocialId(email1.address.address), networkType = SocialNetworks.FORTYTWO, credentials = Some(socialUser)))
          user1
        }

        val path = com.keepit.controllers.website.routes.UserController.changePassword().toString()
        path === "/site/user/password"

        inject[FakeActionAuthenticator].setUser(user)
        val payload = Json.obj("oldPassword" -> oldPwd, "newPassword" -> newPwd)
        val request = FakeRequest("POST", path).withJsonBody(payload)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        contentAsString(result) === Json.obj("success" -> true).toString()
        val uc = db.readOnlyMaster { implicit session =>
          userCredRepo.findByUserIdOpt(user.id.get).get
        }
        hasher.matches(PasswordInfo(hasher = "bcrypt", password = uc.credentials, salt = None), newPwd) === true
      }
    }
  }
}

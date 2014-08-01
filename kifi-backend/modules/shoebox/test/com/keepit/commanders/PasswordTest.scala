package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.FakeActionAuthenticator
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeSocialGraphModule, FakeShoeboxAppSecureSocialModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientModule }
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
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
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeShoeboxAppSecureSocialModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    KeepImportsModule()
  )

  val oldPwd1 = "1234567"
  val newPwd1 = "7654321"
  val emailAddr1 = EmailAddress("foo@42go.com")
  val emailAddr2 = EmailAddress("bar@42go.com")

  def setUp() = {
    db.readWrite { implicit session =>
      val user1 = userRepo.save(User(firstName = "Foo", lastName = "Bar"))
      val email1a = emailAddressRepo.save(UserEmailAddress(userId = user1.id.get, address = emailAddr1))
      val email1b = emailAddressRepo.save(UserEmailAddress(userId = user1.id.get, address = emailAddr2))
      val hasher = Registry.hashers.get("bcrypt").get
      val pwdInfo = hasher.hash(oldPwd1)
      val uc1 = userCredRepo.save(UserCred(userId = user1.id.get, loginName = email1a.address.address, provider = "bcrypt", salt = pwdInfo.salt.getOrElse(""), credentials = pwdInfo.password))
      val socialUserRepo = inject[SocialUserInfoRepo]
      val socialUser = SocialUser(
        identityId = IdentityId(email1a.address.address, "userpass"),
        firstName = user1.firstName,
        lastName = user1.lastName,
        fullName = user1.fullName,
        email = Some(email1a.address.address),
        avatarUrl = None,
        authMethod = AuthenticationMethod.UserPassword,
        passwordInfo = Some(pwdInfo)
      )
      val sui1 = socialUserRepo.save(SocialUserInfo(userId = user1.id, fullName = user1.fullName, socialId = SocialId(email1a.address.address), networkType = SocialNetworks.FORTYTWO, credentials = Some(socialUser)))
      val passwordResetRepo = inject[PasswordResetRepo]
      val resetToken1 = passwordResetRepo.createNewResetToken(user1.id.get, email1a.address)
      (user1, email1a, email1b, sui1, uc1, hasher, pwdInfo, resetToken1)
    }
  }

  def checkPasswordAuth(username: String, password: String, expectSuccess: Boolean) = {
    val path = com.keepit.controllers.core.routes.AuthController.logInWithUserPass().toString()
    path === "/auth/log-in"

    val payload = Json.obj("username" -> username, "password" -> password)
    val request = FakeRequest("POST", path).withJsonBody(payload)
    val result = route(request).get
    if (expectSuccess) {
      status(result) === OK
      contentAsString(result) === Json.obj("uri" -> "/login/after").toString()
    } else {
      status(result) === FORBIDDEN
      contentAsString(result) === Json.obj("error" -> "wrong_password").toString()
    }
  }

  "PasswordHandler" should {

    "handle change password (multi-email)" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user, email1a, email1b, sui, uc, hasher, pwdInfo, _) = setUp()
        val path = com.keepit.controllers.website.routes.UserController.changePassword().toString()
        path === "/site/user/password"

        inject[FakeActionAuthenticator].setUser(user)
        checkPasswordAuth(email1a.address.address, oldPwd1, true)
        checkPasswordAuth(email1b.address.address, oldPwd1, true)
        checkPasswordAuth(email1a.address.address, newPwd1, false)

        val payload = Json.obj("oldPassword" -> oldPwd1, "newPassword" -> newPwd1)
        val request = FakeRequest("POST", path).withJsonBody(payload)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        contentAsString(result) === Json.obj("success" -> true).toString()
        val updated = db.readOnlyMaster { implicit session =>
          userCredRepo.findByUserIdOpt(user.id.get).get
        }
        hasher.matches(pwdInfo, newPwd1) === false
        hasher.matches(PasswordInfo(hasher = "bcrypt", password = updated.credentials, salt = None), newPwd1) === true
        checkPasswordAuth(email1a.address.address, oldPwd1, false)
        checkPasswordAuth(email1a.address.address, newPwd1, true)
        checkPasswordAuth(email1b.address.address, newPwd1, true)
      }
    }

    "handle set password (multi-email)" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user, email1a, email1b, sui, uc, hasher, pwdInfo, resetToken) = setUp()
        val path = com.keepit.controllers.core.routes.AuthController.setPassword().toString
        path === "/password/set"

        inject[FakeActionAuthenticator].setUser(user)
        checkPasswordAuth(email1a.address.address, oldPwd1, true)
        checkPasswordAuth(email1b.address.address, oldPwd1, true)
        checkPasswordAuth(email1a.address.address, newPwd1, false)

        val payload = Json.obj("code" -> resetToken.token, "password" -> newPwd1)
        val request = FakeRequest("POST", path).withJsonBody(payload)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        contentAsString(result) === Json.obj("uri" -> "/").toString()
        val updated = db.readOnlyMaster { implicit session =>
          userCredRepo.findByUserIdOpt(user.id.get).get
        }
        hasher.matches(pwdInfo, newPwd1) === false
        hasher.matches(PasswordInfo(hasher = "bcrypt", password = updated.credentials, salt = None), newPwd1) === true
        checkPasswordAuth(email1a.address.address, oldPwd1, false)
        checkPasswordAuth(email1a.address.address, newPwd1, true)
        checkPasswordAuth(email1b.address.address, newPwd1, true)
      }
    }
  }
}

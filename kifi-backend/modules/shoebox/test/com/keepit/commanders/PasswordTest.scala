package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ KifiSession, FakeUserActionsModule, FakeUserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.core.AuthController
import com.keepit.controllers.website.UserController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, KeepImportsModule }
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ AnyContentAsJson, Result }
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._

import scala.concurrent.Future
import KifiSession._

// todo(ray): figure out how to deal with SecureSocial's dependency on application
class PasswordTest extends Specification with ShoeboxApplicationInjector {

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
    FakeUserActionsModule(),
    FakeCortexServiceClientModule(),
    KeepImportsModule(),
    FakeCuratorServiceClientModule()
  )

  val oldPwd1 = "1234567"
  val newPwd1 = "7654321"
  val emailAddr1 = EmailAddress("foo@42go.com")
  val emailAddr2 = EmailAddress("bar@42go.com")

  def setUp() = {
    db.readWrite { implicit session =>
      val user1 = userRepo.save(User(firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test"))
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

  def checkPasswordAuth(username: String, password: String, expectSuccess: Boolean, userIdOpt: Option[Id[User]] = None)(implicit injector: Injector) = {
    val path = com.keepit.controllers.core.routes.AuthController.logInWithUserPass().toString()
    path === "/auth/log-in"

    val authController = inject[AuthController]
    val payload = AnyContentAsJson(Json.obj("username" -> username, "password" -> password))
    val request = FakeRequest("POST", path).withBody(payload)
    val result: Future[Result] = authController.logInWithUserPass("")(request)
    if (expectSuccess) {
      status(result) === OK
      val sess = session(result)
      userIdOpt foreach { userId =>
        sess.getUserId.get === userId
      }
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

        inject[FakeUserActionsHelper].setUser(user)
        checkPasswordAuth(email1a.address.address, oldPwd1, true, user.id)
        checkPasswordAuth(email1b.address.address, oldPwd1, true, user.id)
        checkPasswordAuth(email1a.address.address, newPwd1, false)

        val userController = inject[UserController]
        val payload = Json.obj("oldPassword" -> oldPwd1, "newPassword" -> newPwd1)
        val request = FakeRequest("POST", path).withBody(payload)
        val result: Future[Result] = userController.changePassword()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        contentAsString(result) === Json.obj("success" -> true).toString()
        val updated = db.readOnlyMaster { implicit session =>
          userCredRepo.findByUserIdOpt(user.id.get).get
        }
        hasher.matches(pwdInfo, newPwd1) === false
        hasher.matches(PasswordInfo(hasher = "bcrypt", password = updated.credentials, salt = None), newPwd1) === true
        checkPasswordAuth(email1a.address.address, oldPwd1, false)
        checkPasswordAuth(email1a.address.address, newPwd1, true, user.id)
        checkPasswordAuth(email1b.address.address, newPwd1, true, user.id)
      }
    }

    "handle set password (multi-email)" in {
      running(new ShoeboxApplication(modules: _*)) {
        val (user, email1a, email1b, sui, uc, hasher, pwdInfo, resetToken) = setUp()
        val path = com.keepit.controllers.core.routes.AuthController.setPassword().toString
        path === "/password/set"

        inject[FakeUserActionsHelper].setUser(user)
        checkPasswordAuth(email1a.address.address, oldPwd1, true, user.id)
        checkPasswordAuth(email1b.address.address, oldPwd1, true)
        checkPasswordAuth(email1a.address.address, newPwd1, false)

        val authController = inject[AuthController]
        val payload = Json.obj("code" -> resetToken.token, "password" -> newPwd1)
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.setPassword()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        contentAsString(result) === Json.obj("uri" -> "/").toString()
        val updated = db.readOnlyMaster { implicit session =>
          userCredRepo.findByUserIdOpt(user.id.get).get
        }
        hasher.matches(pwdInfo, newPwd1) === false
        hasher.matches(PasswordInfo(hasher = "bcrypt", password = updated.credentials, salt = None), newPwd1) === true
        checkPasswordAuth(email1a.address.address, oldPwd1, false)
        checkPasswordAuth(email1a.address.address, newPwd1, true, user.id)
        checkPasswordAuth(email1b.address.address, newPwd1, true)
      }
    }
  }
}

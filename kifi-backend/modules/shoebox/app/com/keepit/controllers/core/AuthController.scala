package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.model.{EmailAddressRepo, SocialUserInfoRepo, UserCredRepo}
import com.keepit.social.{SocialId, SocialNetworks, UserIdentity}

import play.api.Play._
import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraints
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.mvc._
import securesocial.controllers.ProviderController
import securesocial.core._
import securesocial.core.providers.utils.{PasswordHasher, GravatarHelper}

class AuthController @Inject() (
    db: Database,
    userCredRepo: UserCredRepo,
    socialRepo: SocialUserInfoRepo,
    actionAuthenticator: ActionAuthenticator,
    emailRepo: EmailAddressRepo,
    postOffice: LocalPostOffice
  ) extends WebsiteController(actionAuthenticator) with Logging {

  // Note: some of the below code is taken from ProviderController in SecureSocial
  // Logout is still handled by SecureSocial directly.

  private implicit val readsOAuth2Info = Json.reads[OAuth2Info]

  def mobileAuth(providerName: String) = Action(parse.json) { implicit request =>
    // format { "accessToken": "..." }
    val oauth2Info = request.body.asOpt[OAuth2Info]
    val provider = Registry.providers.get(providerName).get
    val filledUser = provider.fillProfile(
      SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth2Info = oauth2Info))
    UserService.find(filledUser.identityId) map { user =>
      val newSession = Events.fire(new LoginEvent(user)).getOrElse(session)
      Authenticator.create(user).fold(
        error => throw error,
        authenticator => Ok(Json.obj("sessionId" -> authenticator.id))
          .withSession(newSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
          .withCookies(authenticator.toCookie)
      )
    } getOrElse NotFound(Json.obj("error" -> "user not found"))
  }

  def login(provider: String) = getAuthAction(provider, isLogin = true)
  def loginByPost(provider: String) = getAuthAction(provider, isLogin = true)
  def link(provider: String) = getAuthAction(provider, isLogin = false)
  def linkByPost(provider: String) = getAuthAction(provider, isLogin = false)

  private def getSession(res: SimpleResult[_], refererAsOriginalUrl: Boolean = false)
      (implicit request: RequestHeader): Session = {
    val sesh = Session.decodeFromCookie(
      res.header.headers.get(SET_COOKIE).flatMap(Cookies.decode(_).find(_.name == Session.COOKIE_NAME)))
    val originalUrlOpt = sesh.get(SecureSocial.OriginalUrlKey) orElse {
      if (refererAsOriginalUrl) request.headers.get(HeaderNames.REFERER) else None
    }
    originalUrlOpt map { url => sesh + (SecureSocial.OriginalUrlKey -> url) } getOrElse sesh
  }

  private def getAuthAction(provider: String, isLogin: Boolean): Action[AnyContent] = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] =>
        res.withSession(if (isLogin) getSession(res) - ActionAuthenticator.FORTYTWO_USER_ID else getSession(res, true))
      case res => res
    }
  }

  private case class RegistrationInfo(email: String, password: String, firstName: String, lastName: String)

  private val passwordForm = Form[String](
    mapping(
      "password" -> tuple("1" -> nonEmptyText, "2" -> nonEmptyText)
        .verifying("Passwords do not match", pw => pw._1 == pw._2).transform(_._1, (a: String) => (a, a))
        .verifying(Constraints.minLength(7))
    )
    (identity)
    (Some(_))
  )

  private val emailPasswordForm = Form[RegistrationInfo](
    mapping(
      "email" -> email.verifying("Email is invalid", email => db.readOnly { implicit s =>
        userCredRepo.findByEmailOpt(email).isEmpty
      }),
      "firstname" -> nonEmptyText,
      "lastname" -> nonEmptyText,
      "password" -> tuple("1" -> nonEmptyText, "2" -> nonEmptyText)
        .verifying("Passwords do not match", pw => pw._1 == pw._2).transform(_._1, (a: String) => (a, a))
        .verifying(Constraints.minLength(7))
    )
    (RegistrationInfo.apply)
    (RegistrationInfo.unapply)
  )

  def signup() = HtmlAction(true)(authenticatedAction = doSignupPage(_), unauthenticatedAction = doSignupPage(_))

  def handleSignup() = HtmlAction(true)(authenticatedAction = doSignup(_), unauthenticatedAction = doSignup(_))

  private def doSignupPage(implicit request: Request[_]): Result = {
    val identity = request.identityOpt
    Ok(views.html.website.signup(
      errorMessage = request.flash.get("error"),
      firstName = identity.map(_.firstName),
      lastName = identity.map(_.lastName),
      email = identity.flatMap(_.email)))
  }

  private val url = current.configuration.getString("application.baseUrl").get

  private def doSignup(implicit request: Request[_]): Result = {
    emailPasswordForm.bindFromRequest.fold(
      formWithErrors => Redirect(routes.AuthController.handleSignup()).flashing(
        "error" -> "Form is invalid"
      ),
      { case RegistrationInfo(email, firstName, lastName, password) =>
        // TODO: sync this with the information in User
        val newIdentity = UserIdentity(
          userId = request.userIdOpt,
          socialUser = SocialUser(
            identityId = IdentityId(email, "userpass"),
            firstName = firstName,
            lastName = lastName,
            fullName = s"$firstName $lastName",
            email = Some(email),
            avatarUrl = GravatarHelper.avatarFor(email),
            authMethod = AuthenticationMethod.UserPassword,
            passwordInfo = Some(Registry.hashers.currentHasher.hash(password))
          ),
          allowSignup = true)
        UserService.save(newIdentity)
        for {
          identity <- request.identityOpt
          socialUserInfo <- db.readOnly { implicit s =>
            socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO)
          }
          userId <- socialUserInfo.userId
        } {
          UserService.save(UserIdentity(userId = Some(userId), socialUser = SocialUser(identity)))
        }
        db.readWrite { implicit s =>
          val emailWithVerification = emailRepo.getByAddressOpt(email)
            .map(emailRepo.saveWithVerificationCode)
            .get
          postOffice.sendMail(ElectronicMail(
            from = EmailAddresses.NOTIFY,
            to = Seq(new EmailAddressHolder { val address = email }),
            subject = "Confirm your email address for Kifi",
            htmlBody = s"Please confirm your email address by going to " +
              s"$url${routes.AuthController.verifyEmail(emailWithVerification.verificationCode.get)}",
            category = ElectronicMailCategory("email_confirmation")
          ))
        }
        Authenticator.create(newIdentity).fold(
          error => Redirect(routes.AuthController.handleSignup()).flashing(
            "error" -> "Error creating user"
          ),
          authenticator => Redirect("/")
            .withSession(session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId)
            .withCookies(authenticator.toCookie)
        )
      })
  }

  def verifyEmail(code: String) = Action { implicit request =>
    db.readWrite { implicit s =>
      if (emailRepo.verifyByCode(code))
        Ok(views.html.website.verifyEmail(success = true))
      else
        BadRequest(views.html.website.verifyEmail(success = false))
    }
  }

  def setNewPassword(code: String) = Action { implicit request =>
    passwordForm.bindFromRequest.fold(
      formWithErrors => Redirect(routes.AuthController.setNewPasswordPage(code)).flashing(
        "error" -> "Passwords must match and be at least 7 characters"
      ), { password =>
        db.readWrite { implicit s =>
          emailRepo.getByCode(code).map { email =>
            emailRepo.verifyByCode(code, clear = true)
            for (sui <- socialRepo.getByUser(email.userId) if sui.networkType == SocialNetworks.FORTYTWO) {
              UserService.save(UserIdentity(
                userId = sui.userId,
                socialUser = sui.credentials.get.copy(
                  passwordInfo = Some(current.plugin[PasswordHasher].get.hash(password))
                )
              ))
            }
            Redirect(routes.AuthController.setNewPasswordPage(code)).flashing("success" -> "true")
          } getOrElse {
            Redirect(routes.AuthController.setNewPasswordPage(code)).flashing("invalid" -> "true")
          }
        }
      })
  }

  def setNewPasswordPage(code: String) = Action { implicit request =>
    db.readWrite { implicit s =>
      val error = request.flash.get("error")
      val isValid = !request.flash.get("invalid").isDefined
      if (!emailRepo.verifyByCode(code) || !isValid || error.isDefined)
        BadRequest(views.html.website.setPassword(valid = isValid, error = error))
      else
        Ok(views.html.website.setPassword(valid = isValid, done = request.flash.get("success").isDefined))
    }
  }

  def resetPasswordPage() = Action { implicit request =>
    Ok(views.html.website.resetPassword(email = request.flash.get("email")))
  }

  def resetPassword() = Action { implicit request =>
    db.readWrite { implicit s =>
      val emailOpt = request.body.asFormUrlEncoded.flatMap(_.get("email")).flatMap(_.headOption)
      val verifiedEmailOpt = emailOpt.flatMap(emailRepo.getByAddressOpt(_)).map(emailRepo.saveWithVerificationCode)
      verifiedEmailOpt map { email =>
        postOffice.sendMail(ElectronicMail(
          from = EmailAddresses.NOTIFY,
          to = Seq(new EmailAddressHolder { val address = email.address }),
          subject = "Reset your password",
          htmlBody = s"You can set a new password by going to " +
              s"$url${routes.AuthController.setNewPassword(email.verificationCode.get)}",
          category = ElectronicMailCategory("reset_password")
        ))
        Redirect(routes.AuthController.resetPasswordPage()).flashing("email" -> email.address)
      } getOrElse {
        Redirect(routes.AuthController.resetPasswordPage())
      }
    }
  }
}

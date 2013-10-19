package com.keepit.controllers.core

import scala.Some

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.model._
import com.keepit.social.SocialId
import com.keepit.social.UserIdentity
import com.keepit.social.{SocialNetworkType, SocialNetworks}
import com.keepit.common.KestrelCombinator

import play.api.Play._
import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraints
import play.api.http.HeaderNames
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import securesocial.controllers.ProviderController
import securesocial.core._
import securesocial.core.providers.utils.{PasswordHasher, GravatarHelper}

sealed abstract class AuthType

object AuthType {
  case object Login extends AuthType
  case object Signup extends AuthType
  case object Link extends AuthType
  case object LoginAndLink extends AuthType
}

object AuthController {
  val LinkWithKey = "linkWith"
}

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

  def login(provider: String, format: String) = getAuthAction(provider, AuthType.Login, format)
  def loginByPost(provider: String, format: String) = getAuthAction(provider, AuthType.Login, format)
  def loginWithUserPass(format: String) = getAuthAction("userpass", AuthType.Login, format)

  def link(provider: String) = getAuthAction(provider, AuthType.Link)
  def linkByPost(provider: String) = getAuthAction(provider, AuthType.Link)

  def signup(provider: String) = getAuthAction(provider, AuthType.Signup, "html")
  def signupByPost(provider: String) = getAuthAction(provider, AuthType.Signup, "html")

  // log in with username/password and link the account with a provider
  def passwordLoginAndLink(provider: String) = getAuthAction(provider, AuthType.LoginAndLink)

  private def getSession(res: SimpleResult[_], originalUrl: Option[String] = None)
      (implicit request: RequestHeader): Session = {
    val sesh = Session.decodeFromCookie(
      res.header.headers.get(SET_COOKIE).flatMap(Cookies.decode(_).find(_.name == Session.COOKIE_NAME)))
    val originalUrlOpt = sesh.get(SecureSocial.OriginalUrlKey) orElse originalUrl
    originalUrlOpt map { url => sesh + (SecureSocial.OriginalUrlKey -> url) } getOrElse sesh
  }

  private def getAuthAction(provider: String, authType: AuthType, format: String = "json"): Action[AnyContent] = Action { request =>
    val augmentedRequest = augmentRequestWithTag(request, "format" -> format)

    val actualProvider = if (authType == AuthType.LoginAndLink) SocialNetworks.FORTYTWO.authProvider else provider
    ProviderController.authenticate(actualProvider)(augmentedRequest) match {
      case res: SimpleResult[_] =>
        res.withSession(authType match {
          case AuthType.Login => getSession(res)(augmentedRequest) - ActionAuthenticator.FORTYTWO_USER_ID
          case AuthType.Signup => getSession(res)(augmentedRequest) - ActionAuthenticator.FORTYTWO_USER_ID +
            (SecureSocial.OriginalUrlKey -> routes.AuthController.signupPage().url)
          case AuthType.Link => getSession(res, augmentedRequest.headers.get(HeaderNames.REFERER))(augmentedRequest)
          case AuthType.LoginAndLink => getSession(res, None)(augmentedRequest) - ActionAuthenticator.FORTYTWO_USER_ID -
            SecureSocial.OriginalUrlKey + (AuthController.LinkWithKey -> provider)
        })
      case res => res
    }
  }

  private def augmentRequestWithTag[T](request: Request[T], additionalTags: (String, String)*): Request[T] = {
    new WrappedRequest[T](request) {
      override def tags = request.tags ++ additionalTags.toMap
    }
  }

  private case class RegistrationInfo(email: String, password: String, firstName: String, lastName: String)
  private case class ConfirmationInfo(firstName: String, lastName: String)
  private case class EmailPassword(email: String, password: String)

  private val passwordForm = Form[String](
    mapping(
      "password" -> tuple("1" -> nonEmptyText, "2" -> nonEmptyText)
        .verifying("Passwords do not match", pw => pw._1 == pw._2).transform(_._1, (a: String) => (a, a))
        .verifying(Constraints.minLength(7))
    )
    (identity)
    (Some(_))
  )

  private val registrationInfoForm = Form[RegistrationInfo](
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

  private val confirmationInfoForm = Form[ConfirmationInfo](
    mapping("firstname" -> nonEmptyText, "lastname" -> nonEmptyText)(ConfirmationInfo.apply)(ConfirmationInfo.unapply)
  )

  private val emailPasswordForm = Form[EmailPassword](
    mapping(
      "email" -> email,
      "password" -> text.verifying("password_too_short", pw => pw.length >= 7)
    )
    (EmailPassword.apply)
    (EmailPassword.unapply)
  )

  def signupPage() = HtmlAction(true)(authenticatedAction = doSignupPage(_), unauthenticatedAction = doSignupPage(_))

  def handleSignup() = HtmlAction(true)(authenticatedAction = doSignup(_), unauthenticatedAction = doSignup(_))

  // Initial user/pass signup JSON action
  def userPasswordSignup() = JsonToJsonAction(true)(
    authenticatedAction = userPasswordSignupAction(_),
    unauthenticatedAction = userPasswordSignupAction(_)
  )

  private def userPasswordSignupAction(implicit request: Request[JsValue]) = {

    val home = com.keepit.controllers.website.routes.HomeController.home()
    emailPasswordForm.bindFromRequest.fold(
    formWithErrors => Forbidden(Json.obj("error" -> formWithErrors.errors.headOption.map(f => f.message))),
    { case EmailPassword(email, password) =>
      val hasher = Registry.hashers.currentHasher

      db.readOnly { implicit s => socialRepo.getOpt(SocialId(email), SocialNetworks.FORTYTWO) }.collect {
        case sui if sui.credentials.isDefined && sui.userId.isDefined =>
          val identity = sui.credentials.get
          if (hasher.matches(identity.passwordInfo.get, password)) {
            Authenticator.create(identity).fold(
              error => Forbidden(Json.obj("error" -> Seq("user_exists_failed_auth"))),
              authenticator =>
                Ok(Json.obj("success"->"true", "email" -> email, "new_account" -> false))
                  .withNewSession
                  .withCookies(authenticator.toCookie)
            )
          } else {
            Forbidden(Json.obj("error" -> Seq("user_exists_failed_auth")))
          }
      }.getOrElse {
        val pinfo = hasher.hash(password)
        val newIdentity = saveUserIdentity(None, request.identityOpt, email, pinfo, isComplete = false)
        Authenticator.create(newIdentity).fold(
          error => Forbidden(Json.obj("error" -> Seq("couldnt_create_user"))),
          authenticator =>
            Ok(Json.obj("success"->"true", "email" -> email, "new_account" -> true))
              .withSession(session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId)
              .withCookies(authenticator.toCookie)
        )
      }
    })
  }

  // Greg's first user/pass signup handler
  def handleUsernamePasswordSignup() = HtmlAction(true)({ _ => Redirect("/") }, { implicit request =>
    val home = com.keepit.controllers.website.routes.HomeController.home()
    val signup = routes.AuthController.signupPage()

    emailPasswordForm.bindFromRequest.fold(
      formWithErrors => Redirect(home).flashing("error" -> "Form is invalid"),
      { case EmailPassword(email, password) =>
        val hasher = Registry.hashers.currentHasher

        db.readOnly { implicit s => socialRepo.getOpt(SocialId(email), SocialNetworks.FORTYTWO) }.collect {
          case sui if sui.credentials.isDefined && sui.userId.isDefined =>
            val identity = sui.credentials.get
            if (hasher.matches(identity.passwordInfo.get, password)) {
              Authenticator.create(identity).fold(
                error => Redirect(home).flashing("error" -> "Email exists; login failed"),
                authenticator =>
                  Redirect(s"${home.url}?m=0")
                      .withSession(session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId)
                      .withCookies(authenticator.toCookie)
              )
            } else {
              Redirect(home).flashing("error" -> "A user with that email exists but the password was invalid")
            }
        }.getOrElse {
          val pinfo = hasher.hash(password)
          val newIdentity = saveUserIdentity(request.userIdOpt, request.identityOpt, email, pinfo, isComplete = false)
          Authenticator.create(newIdentity).fold(
            error => Redirect(signup).flashing("error" -> "Error creating user"),
            authenticator =>
              Redirect(signup)
                .withSession(session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId)
                .withCookies(authenticator.toCookie)
          )
        }
      })
  })

  // Greg's 2nd stage signup page
  private def doSignupPage(implicit request: Request[_]): Result = {
    def hasEmail(identity: Identity): Boolean = db.readOnly { implicit s =>
      identity.email.flatMap(emailRepo.getByAddressOpt(_)).isDefined
    }

    (request.userOpt, request.identityOpt) match {
      case (Some(user), _) if user.state != UserStates.INCOMPLETE_SIGNUP =>
        Redirect(s"${com.keepit.controllers.website.routes.HomeController.home.url}?m=0")
      case (Some(user), identityOpt) =>
        Ok(views.html.website.completeSignup(
          errorMessage = request.flash.get("error"),
          email = identityOpt.flatMap(_.email)))
      case (None, Some(identity)) if hasEmail(identity) =>
        val error = request.flash.get("error").map { _ => "Login failed" }
        Ok(views.html.website.emailExists(
          identity.email.get, SocialNetworkType(identity.identityId.providerId), error))
      case (None, identityOpt) =>
        Ok(views.html.website.signup(
          errorMessage = request.flash.get("error"),
          network = identityOpt.map(id => SocialNetworkType(id.identityId.providerId)),
          firstName = identityOpt.map(_.firstName),
          lastName = identityOpt.map(_.lastName),
          email = identityOpt.flatMap(_.email)))
    }
  }

  private val url = current.configuration.getString("application.baseUrl").get

  // Greg's 2nd signup action
  private def doSignup(implicit request: Request[AnyContent]): Result = {
    val isConfirmation = request.body.asFormUrlEncoded.exists(_.get("confirm").isDefined)

    def finishSignup(newIdentity: Identity): Result = {
      db.readWrite { implicit s =>
        val email = newIdentity.email.get
        val emailWithVerification =
          emailRepo.getByAddressOpt(email)
            .map(emailRepo.saveWithVerificationCode)
            .get
        postOffice.sendMail(ElectronicMail(
          from = EmailAddresses.NOTIFICATIONS,
          to = Seq(GenericEmailAddress(email)),
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
    }

    if (isConfirmation) {
      confirmationInfoForm.bindFromRequest.fold(
        formWithErrors => Redirect(routes.AuthController.signupPage()).flashing(
          "error" -> "Form is invalid"
        ),
        { case ConfirmationInfo(firstName, lastName) =>
          val identity = request.identityOpt.get
          val pinfo = identity.passwordInfo.get
          val email = identity.email.get
          val newIdentity = saveUserIdentity(request.userIdOpt, request.identityOpt, email = email, passwordInfo = pinfo,
            firstName = firstName, lastName = lastName, isComplete = true)

          finishSignup(newIdentity)
        })
    } else {
      registrationInfoForm.bindFromRequest.fold(
        formWithErrors => Redirect(routes.AuthController.signupPage()).flashing(
          "error" -> "Form is invalid"
        ),
        { case RegistrationInfo(email, firstName, lastName, password) =>
          val pinfo = Registry.hashers.currentHasher.hash(password)
          val newIdentity = saveUserIdentity(request.userIdOpt, request.identityOpt,
            email = email, passwordInfo = pinfo, firstName = firstName, lastName = lastName, isComplete = true)

          finishSignup(newIdentity)
        })
    }
  }

  private def saveUserIdentity(userIdOpt: Option[Id[User]], identityOpt: Option[Identity],
      email: String, passwordInfo: PasswordInfo,
      firstName: String = "", lastName: String = "", isComplete: Boolean = true): UserIdentity = {
    val newIdentity = UserIdentity(
      userId = userIdOpt,
      socialUser = SocialUser(
        identityId = IdentityId(email, SocialNetworks.FORTYTWO.authProvider),
        firstName = if (isComplete || firstName.nonEmpty) firstName else email,
        lastName = lastName,
        fullName = s"$firstName $lastName",
        email = Some(email),
        avatarUrl = GravatarHelper.avatarFor(email),
        authMethod = AuthenticationMethod.UserPassword,
        passwordInfo = Some(passwordInfo)
      ),
      allowSignup = true,
      isComplete = isComplete)
    UserService.save(newIdentity)
    for {
      identity <- identityOpt
      socialUserInfo <- db.readOnly { implicit s =>
        socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO)
      }
      userId <- socialUserInfo.userId
    } {
      UserService.save(UserIdentity(userId = Some(userId), socialUser = SocialUser(identity)))
    }
    newIdentity
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
          from = EmailAddresses.NOTIFICATIONS,
          to = Seq(GenericEmailAddress(email.address)),
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

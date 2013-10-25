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
import play.api.libs.iteratee.Enumerator
import play.api.Play

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
    userRepo: UserRepo,
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

  private def getAuthAction(provider: String, authType: AuthType, format: String = "html"): Action[AnyContent] = Action { request =>
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
  private case class ConfirmationInfo(firstName: String, lastName: String, picToken: Option[String])
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

  // Finalize account
  def signupPage() = HtmlAction(true)(authenticatedAction = doFinalizePage(_), unauthenticatedAction = doFinalizePage(_))


  // Initial user/pass signup JSON action
  def userPasswordSignup() = JsonToJsonAction(true)(
    authenticatedAction = userPasswordSignupAction(_),
    unauthenticatedAction = userPasswordSignupAction(_)
  )
  private val emailPasswordForm = Form[EmailPassword](
    mapping(
      "email" -> email,
      "password" -> text.verifying("password_too_short", pw => pw.length >= 7)
    )(EmailPassword.apply)(EmailPassword.unapply)
  )
  private def userPasswordSignupAction(implicit request: Request[JsValue]) = {
    val home = com.keepit.controllers.website.routes.HomeController.home()
    emailPasswordForm.bindFromRequest.fold(
      formWithErrors => Forbidden(Json.obj("error" -> formWithErrors.errors.head.message)),
      { case EmailPassword(email, password) =>
        val hasher = Registry.hashers.currentHasher

        db.readOnly { implicit s =>
          socialRepo.getOpt(SocialId(email), SocialNetworks.FORTYTWO)
        }.collect {
          case sui if sui.credentials.isDefined && sui.userId.isDefined =>
            val identity = sui.credentials.get
            if (hasher.matches(identity.passwordInfo.get, password)) {
              Authenticator.create(identity).fold(
                error => Status(500)("0"),
                authenticator => {
                  val needsToFinalize = db.readOnly { implicit session =>
                    userRepo.get(sui.userId.get).state == UserStates.INCOMPLETE_SIGNUP
                  }
                  Ok(Json.obj("success"-> true, "email" -> email, "new_account" -> false, "needsToFinalize" -> needsToFinalize))
                    .withNewSession
                    .withCookies(authenticator.toCookie)
                }
              )
            } else {
              Forbidden(Json.obj("error" -> "user_exists_failed_auth"))
            }
        }.getOrElse {
          val pInfo = hasher.hash(password)
          val newIdentity = saveUserPasswordIdentity(None, request.identityOpt, email, pInfo, isComplete = false)
          Authenticator.create(newIdentity).fold(
            error => Status(500)("0"),
            authenticator =>
              Ok(Json.obj("success"-> true, "email" -> email, "new_account" -> true))
                .withNewSession
                .withCookies(authenticator.toCookie)
          )
        }
      }
    )
  }


  private def doFinalizePage(implicit request: Request[_]): Result = {
    def hasEmail(identity: Identity): Boolean = db.readOnly { implicit s =>
      identity.email.flatMap(emailRepo.getByAddressOpt(_)).isDefined
    }

    (request.userOpt, request.identityOpt) match {
      case (Some(user), _) if user.state != UserStates.INCOMPLETE_SIGNUP =>
        // Complete user, they don't need to be here!
        Redirect(s"${com.keepit.controllers.website.routes.HomeController.home.url}?m=0")
      case (Some(user), Some(identity)) =>
        // User exists, is incomplete
        Ok(views.html.signup.finalizeEmail(
          emailAddress = identity.email.getOrElse(""),
          picturePath = identity.avatarUrl.getOrElse("")
        ))
      case (Some(user), None) =>
        // User but no identity. Huh?
        // TODO(andrew): Figure this one out
        Ok("user, no identity")
      case (None, Some(identity)) if hasEmail(identity) =>
        // No user exists, has identity and identity has an email in our records
        // Bad login? Trying to discover when this state can happen, will get back to this.
        val error = request.flash.get("error").map { _ => "Login failed" }
        Ok("No user, identity, has email")
      case (None, Some(identity)) =>
        // No user exists, so is social
        val triedToLoginWithNoAccount = request.flash.get("signin_error").exists(_ == "no_account")
        Ok(views.html.signup.finalizeSocial(
          firstName = User.sanitizeName(identity.firstName),
          lastName = User.sanitizeName(identity.lastName),
          emailAddress = identity.email.getOrElse(""),
          picturePath = identity.avatarUrl.getOrElse(""),
          triedToLoginWithNoAccount = triedToLoginWithNoAccount
        ))
      case (None, None) =>
        // TODO(andrew): Forward user to initial signup page
        Ok("You should sign up!")
    }
  }

  // user/email finalize action (new)
  def userPassFinalizeAccountAction() = JsonToJsonAction(true)(authenticatedAction = doUserPassFinalizeAccountAction(_), unauthenticatedAction = doUserPassFinalizeAccountAction(_))
  private val userPassFinalizeAccountForm = Form[ConfirmationInfo](
    mapping("firstName" -> nonEmptyText, "lastName" -> nonEmptyText, "picToken" -> optional(text))(ConfirmationInfo.apply)(ConfirmationInfo.unapply)
  )
  def doUserPassFinalizeAccountAction(implicit request: Request[JsValue]): Result = {
    userPassFinalizeAccountForm.bindFromRequest.fold(
    formWithErrors => Forbidden(Json.obj("error" -> "user_exists_failed_auth")),
    { case ConfirmationInfo(firstName, lastName, picToken) =>
      val identity = request.identityOpt.get
      val pinfo = identity.passwordInfo.get
      val email = identity.email.get
      val newIdentity = saveUserPasswordIdentity(request.userIdOpt, request.identityOpt, email = email, passwordInfo = pinfo,
        firstName = firstName, lastName = lastName, isComplete = true)

      finishSignup(newIdentity, true)
    })
  }

  // social finalize action (new)
  def socialFinalizeAccountAction() = JsonToJsonAction(true)(authenticatedAction = doSocialFinalizeAccountAction(_), unauthenticatedAction = doSocialFinalizeAccountAction(_))
  private val socialFinalizeAccountForm = Form[RegistrationInfo](
    mapping(
      "email" -> email.verifying("email_exists_for_other_user", email => db.readOnly { implicit s =>
        userCredRepo.findByEmailOpt(email).isEmpty
      }),
      "firstName" -> nonEmptyText,
      "lastName" -> nonEmptyText,
      "password" -> text.verifying("password_too_short", pw => pw.length >= 7)
    )
      (RegistrationInfo.apply)
      (RegistrationInfo.unapply)
  )
  def doSocialFinalizeAccountAction(implicit request: Request[JsValue]): Result = {
    socialFinalizeAccountForm.bindFromRequest.fold(
    formWithErrors => Forbidden(Json.obj("error" -> formWithErrors.errors.head.message)),
    {
      case RegistrationInfo(email, firstName, lastName, password) =>
        request.identityOpt
        val pinfo = Registry.hashers.currentHasher.hash(password)
        val newIdentity = saveUserPasswordIdentity(request.userIdOpt, request.identityOpt,
          email = email, passwordInfo = pinfo, firstName = firstName, lastName = lastName, isComplete = true)

        val emailConfirmedBySocialNetwork = request.identityOpt.flatMap(_.email).exists(_.trim == email.trim)
        finishSignup(newIdentity, emailConfirmedBySocialNetwork)
    })
  }

  private def finishSignup(newIdentity: Identity, emailConfirmedAlready: Boolean)(implicit request: Request[JsValue]): Result = {
    if (!emailConfirmedAlready) {
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
    }

    Authenticator.create(newIdentity).fold(
      error => Status(500)("0"),
      authenticator => Ok
        .withNewSession
        .withCookies(authenticator.toCookie)
    )
  }

  def OkStreamFile(filename: String) =
    Ok.stream(Enumerator.fromStream(Play.resourceAsStream(filename).get)) as HTML


  private val url = current.configuration.getString("application.baseUrl").get

  private def saveUserPasswordIdentity(userIdOpt: Option[Id[User]], identityOpt: Option[Identity],
      email: String, passwordInfo: PasswordInfo,
      firstName: String = "", lastName: String = "", isComplete: Boolean = true): UserIdentity = {
    val newIdentity = UserIdentity(
      userId = userIdOpt,
      socialUser = SocialUser(
        identityId = IdentityId(email, SocialNetworks.FORTYTWO.authProvider),
        firstName = User.sanitizeName(if (isComplete || firstName.nonEmpty) firstName else email),
        lastName = User.sanitizeName(lastName),
        fullName = User.sanitizeName(s"$firstName $lastName"),
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

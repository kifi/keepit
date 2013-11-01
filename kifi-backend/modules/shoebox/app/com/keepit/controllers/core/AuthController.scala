package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import com.keepit.common.controller.{AuthenticatedRequest, WebsiteController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.model._
import com.keepit.social.SocialId
import com.keepit.social.UserIdentity
import com.keepit.social.{SocialNetworkType, SocialNetworks}
import com.keepit.common.KestrelCombinator

import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.Play._
import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraints
import play.api.http.HeaderNames
import play.api.libs.json.{JsNumber, JsObject, JsString, JsValue, Json}
import play.api.mvc._
import securesocial.controllers.ProviderController
import securesocial.core._
import securesocial.core.providers.utils.{PasswordHasher, GravatarHelper}
import play.api.libs.iteratee.Enumerator
import play.api.Play
import com.keepit.common.store.{S3UserPictureConfig, ImageCropAttributes, S3ImageStore}
import scala.util.{Failure, Success}
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}

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
    postOffice: LocalPostOffice,
    userValueRepo: UserValueRepo,
    s3ImageStore: S3ImageStore,
    airbrakeNotifier: AirbrakeNotifier,
    emailAddressRepo: EmailAddressRepo
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
  def postLogin() = HtmlAction(allowPending = true)(authenticatedAction = { implicit request =>
    val linkWith = request.session.get(AuthController.LinkWithKey)
    if (request.user.state == UserStates.PENDING) {
      // User is pending!
      Redirect("/")
    } else if (request.user.state == UserStates.INCOMPLETE_SIGNUP) {
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else if (request.kifiInstallationId.isEmpty && !hasSeenInstall) {
      Redirect(com.keepit.controllers.website.routes.HomeController.install())
    } else {
      Redirect(session.get(SecureSocial.OriginalUrlKey).getOrElse("/"))
    }
  }, unauthenticatedAction = { implicit request =>
    val newSignup = current.configuration.getBoolean("newSignup").getOrElse(false)
    if (newSignup && request.identityOpt.isDefined) {

      // TODO(andrew): Handle special case. User tried to log in (not sign up) with social network, email exists in system but social user doesn't.
      //

      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
        .flashing("signin_error" -> "no_account")
    }
    else{
      Redirect("/") // error??
      //      Ok(views.html.website.welcome(newSignup = newSignup, msg = request.flash.get("error")))
    }
  })

  def link(provider: String) = getAuthAction(provider, AuthType.Link)
  def linkByPost(provider: String) = getAuthAction(provider, AuthType.Link)

  def signup(provider: String) = getAuthAction(provider, AuthType.Signup, "html")
  def signupByPost(provider: String) = getAuthAction(provider, AuthType.Signup, "html")

  // log in with username/password and link the account with a provider
  def passwordLoginAndLink(provider: String) = getAuthAction(provider, AuthType.LoginAndLink)

  // --
  // Utility methods
  // --

  private def hasSeenInstall(implicit request: AuthenticatedRequest[_]): Boolean = {
    db.readOnly { implicit s => userValueRepo.getValue(request.userId, "has_seen_install").exists(_.toBoolean) }
  }


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

  private case class EmailPassword(email: String, password: String)

  // TODO: something different if already logged in?
  def signinPage() = HtmlAction(allowPending = true)(authenticatedAction = doLoginPage(_), unauthenticatedAction = doLoginPage(_))

  // Finalize account
  def signupPage() = HtmlAction(allowPending = true)(authenticatedAction = doFinalizePage(_), unauthenticatedAction = doFinalizePage(_))


  // Initial user/pass signup JSON action
  def userPasswordSignup() = JsonToJsonAction(allowPending = true)(
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
    // For email logins, a (emailString, password) is tied to a user. This email string
    // has no direct connection to a user's actual active email address. So, we need to
    // keep in mind that whenever the user supplies an email address, it may or may not
    // be related to what's their (emailString, password) login combination.

    val home = com.keepit.controllers.website.routes.HomeController.home()
    emailPasswordForm.bindFromRequest.fold(
      hasErrors = formWithErrors => Forbidden(Json.obj("error" -> formWithErrors.errors.head.message)),
      success = { case EmailPassword(emailAddress, password) =>
        val hasher = Registry.hashers.currentHasher

        db.readOnly { implicit s =>
          socialRepo.getOpt(SocialId(emailAddress), SocialNetworks.FORTYTWO).map(s => (true, s)) orElse {
            emailAddressRepo.getByAddressOpt(emailAddress).map {
              case emailAddr if emailAddr.state == EmailAddressStates.VERIFIED =>
                (true, socialRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).headOption)
              case emailAddr =>
                // Someone is trying to register with someone else's unverified + non-login email address.
                (false, socialRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).headOption)
            }
            None
          }
        }.collect {
          case (emailIsVerifiedOrPrimary, sui) if sui.credentials.isDefined && sui.userId.isDefined =>
            // Social user exists with these credentials
            val identity = sui.credentials.get
            if (hasher.matches(identity.passwordInfo.get, password)) {
              Authenticator.create(identity).fold(
                error => Status(INTERNAL_SERVER_ERROR)("0"),
                authenticator => {
                  val finalized = db.readOnly { implicit session =>
                    userRepo.get(sui.userId.get).state != UserStates.INCOMPLETE_SIGNUP
                  }
                  Ok(Json.obj("success"-> true, "email" -> emailAddress, "new_account" -> false, "finalized" -> finalized))
                    .withNewSession
                    .withCookies(authenticator.toCookie)
                }
              )
            } else {
              // emailIsVerifiedOrPrimary lets you know if the email is verified to the user.
              // Deal with later?
              Forbidden(Json.obj("error" -> "user_exists_failed_auth"))
            }
        }.getOrElse {
          val pInfo = hasher.hash(password)
          val (newIdentity, _) = saveUserPasswordIdentity(None, request.identityOpt, emailAddress, pInfo, isComplete = false)
          Authenticator.create(newIdentity).fold(
            error => Status(INTERNAL_SERVER_ERROR)("0"),
            authenticator =>
              Ok(Json.obj("success"-> true, "email" -> emailAddress, "new_account" -> true))
                .withNewSession
                .withCookies(authenticator.toCookie)
          )
        }
      }
    )
  }

  private def doLoginPage(implicit request: Request[_]): Result = {
    Ok(views.html.signup.auth("login"))
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
        // Haven't run into this one. Redirecting user to logout, ideally to fix their cookie situation
        Redirect(securesocial.controllers.routes.LoginPage.logout)
      case (None, Some(identity)) if hasEmail(identity) =>
        // No user exists, has identity and identity has an email in our records
        // Happens when user tries to sign up, but account exists with email address which belongs to current user
        // todo(andrew): integrate loginAndLink form
        val error = request.flash.get("error").map { _ => "Login failed" }
        Ok("No user, identity, has email")
      case (None, Some(identity)) if request.flash.get("signin_error").exists(_ == "no_account") =>
        // No user exists, social login was attempted. Let user choose what to do next.
        // todo: Handle if we know who they are by their email
        Ok(views.html.signup.loggedInWithWrongNetwork(
          network = SocialNetworkType(identity.identityId.providerId)
        ))
      case (None, Some(identity)) =>
        // No user exists, must finalize

        val picture = identity.identityId.providerId match {
          case "facebook" =>
            s"//graph.facebook.com/${identity.identityId.userId}/picture?width=200&height=200"
          case _ => identity.avatarUrl.getOrElse(S3UserPictureConfig.defaultImage)
        }

        Ok(views.html.signup.finalizeSocial(
          firstName = User.sanitizeName(identity.firstName),
          lastName = User.sanitizeName(identity.lastName),
          emailAddress = identity.email.getOrElse(""),
          picturePath = picture
        ))
      case (None, None) =>
        // TODO(andrew): Forward user to initial signup page
        Ok("You should sign up!")
    }
  }

  // user/email finalize action (new)
  def userPassFinalizeAccountAction() = JsonToJsonAction(allowPending = true)(authenticatedAction = doUserPassFinalizeAccountAction(_), unauthenticatedAction = _ => Forbidden(JsNumber(0)))
  private case class EmailPassFinalizeInfo(
    firstName: String,
    lastName: String,
    picToken: Option[String],
    picWidth: Option[Int],
    picHeight: Option[Int],
    cropX: Option[Int],
    cropY: Option[Int],
    cropSize: Option[Int])
  private val userPassFinalizeAccountForm = Form[EmailPassFinalizeInfo](mapping(
      "firstName" -> nonEmptyText,
      "lastName" -> nonEmptyText,
      "picToken" -> optional(text),
      "picWidth" -> optional(number),
      "picHeight" -> optional(number),
      "cropX" -> optional(number),
      "cropY" -> optional(number),
      "cropSize" -> optional(number)
    )(EmailPassFinalizeInfo.apply)(EmailPassFinalizeInfo.unapply))
  def doUserPassFinalizeAccountAction(implicit request: AuthenticatedRequest[JsValue]): Result = {
    userPassFinalizeAccountForm.bindFromRequest.fold(
    formWithErrors => Forbidden(Json.obj("error" -> "user_exists_failed_auth")),
    { case EmailPassFinalizeInfo(firstName, lastName, picToken, picHeight, picWidth, cropX, cropY, cropSize) =>
      val identity = request.identityOpt.get
      val pinfo = identity.passwordInfo.get
      val email = identity.email.get
      val (newIdentity, userId) = saveUserPasswordIdentity(request.userIdOpt, request.identityOpt, email = email, passwordInfo = pinfo,
        firstName = firstName, lastName = lastName, isComplete = true)

      val cropAttributes = parseCropForm(picHeight, picWidth, cropX, cropY, cropSize) tap (r => log.info(s"Cropped attributes for ${request.user.id.get}: " + r))
      picToken.map { token =>
        s3ImageStore.copyTempFileToUserPic(request.user.id.get, request.user.externalId, token, cropAttributes)
      }

      finishSignup(newIdentity, emailConfirmedAlready = false)
    })
  }

  // social finalize action (new)
  def socialFinalizeAccountAction() = JsonToJsonAction(allowPending = true)(authenticatedAction = doSocialFinalizeAccountAction(_), unauthenticatedAction = doSocialFinalizeAccountAction(_))
  private case class SocialFinalizeInfo(
    email: String,
    password: String,
    firstName: String,
    lastName: String,
    picToken: Option[String],
    picHeight: Option[Int], picWidth: Option[Int],
    cropX: Option[Int], cropY: Option[Int],
    cropSize: Option[Int])
  private val socialFinalizeAccountForm = Form[SocialFinalizeInfo](
    mapping(
      "email" -> email.verifying("email_exists_for_other_user", email => db.readOnly { implicit s =>
        userCredRepo.findByEmailOpt(email).isEmpty
      }),
      "firstName" -> nonEmptyText,
      "lastName" -> nonEmptyText,
      "password" -> text.verifying("password_too_short", pw => pw.length >= 7),
      "picToken" -> optional(text),
      "picHeight" -> optional(number),
      "picWidth" -> optional(number),
      "cropX" -> optional(number),
      "cropY" -> optional(number),
      "cropSize" -> optional(number)
    )
      (SocialFinalizeInfo.apply)
      (SocialFinalizeInfo.unapply)
  )
  def doSocialFinalizeAccountAction(implicit request: Request[JsValue]): Result = {
    socialFinalizeAccountForm.bindFromRequest.fold(
    formWithErrors => BadRequest(Json.obj("error" -> formWithErrors.errors.head.message)),
    {
      case SocialFinalizeInfo(emailAddress, firstName, lastName, password, picToken, picHeight, picWidth, cropX, cropY, cropSize) =>

        val pinfo = Registry.hashers.currentHasher.hash(password)

        val (emailPassIdentity, userId) = saveUserPasswordIdentity(request.userIdOpt, request.identityOpt,
          email = emailAddress, passwordInfo = pinfo, firstName = firstName, lastName = lastName, isComplete = true)

        val user = db.readOnly { implicit session =>
          userRepo.get(userId)
        }

        val cropAttributes = parseCropForm(picHeight, picWidth, cropX, cropY, cropSize) tap (r => log.info(s"Cropped attributes for ${user.id.get}: " + r))
        picToken.map { token =>
          s3ImageStore.copyTempFileToUserPic(user.id.get, user.externalId, token, cropAttributes)
        }

        val emailConfirmedBySocialNetwork = request.identityOpt.flatMap(_.email).exists(_.trim == emailAddress.trim)

        finishSignup(emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork)
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
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => Ok("1").withNewSession.withCookies(authenticator.toCookie)
    )
  }

  private def parseCropForm(picHeight: Option[Int], picWidth: Option[Int], cropX: Option[Int], cropY: Option[Int], cropSize: Option[Int]) = {
    for {
      h <- picHeight
      w <- picWidth
      x <- cropX
      y <- cropY
      s <- cropSize
    } yield ImageCropAttributes(w = w, h = h, x = x, y = y, s = s)
  }

  def OkStreamFile(filename: String) =
    Ok.stream(Enumerator.fromStream(Play.resourceAsStream(filename).get)) as HTML


  private val url = current.configuration.getString("application.baseUrl").get

  private def saveUserPasswordIdentity(userIdOpt: Option[Id[User]], identityOpt: Option[Identity],
      email: String, passwordInfo: PasswordInfo,
      firstName: String = "", lastName: String = "", isComplete: Boolean): (UserIdentity, Id[User]) = {
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

    UserService.save(newIdentity) // Kifi User is created here if it doesn't exist

    val userIdFromEmailIdentity = for {
      identity <- identityOpt
      socialUserInfo <- db.readOnly { implicit s =>
        socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO)
      }
      userId <- socialUserInfo.userId
    } yield {
      UserService.save(UserIdentity(userId = Some(userId), socialUser = SocialUser(identity)))
      userId
    }

    val user = userIdFromEmailIdentity.orElse {
      db.readOnly { implicit s =>
        socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO).map(_.userId).flatten
      }
    }

    (newIdentity, user.get)
  }

  def verifyEmail(code: String) = Action { implicit request =>
    db.readWrite { implicit s =>
      if (emailRepo.verifyByCode(code))
        Ok(views.html.website.verifyEmail(success = true))
      else
        BadRequest(views.html.website.verifyEmail(success = false))
    }
  }

  private val passwordForm = Form[String](
    mapping(
      "password" -> tuple("1" -> nonEmptyText, "2" -> nonEmptyText)
        .verifying("Passwords do not match", pw => pw._1 == pw._2).transform(_._1, (a: String) => (a, a))
        .verifying(Constraints.minLength(7))
    )(identity)(Some(_))
  )
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

  // todo(andrew): Send reset email to ALL verified email addresses of an account (unless none, in which case, to one)

  def resetPassword() = JsonToJsonAction(allowPending = true)(authenticatedAction = doResetPassword(_), unauthenticatedAction = doResetPassword(_))
  def doResetPassword(implicit request: Request[JsValue]): Result = {
    (request.body \ "email").asOpt[JsString] map { emailAddr =>
      db.readWrite { implicit s =>
        emailRepo.getByAddressOpt(emailAddr.value).map(emailRepo.saveWithVerificationCode).map { email =>
          postOffice.sendMail(ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS,
            to = Seq(GenericEmailAddress(email.address)),
            subject = "Reset your password",
            htmlBody = "You can set a new password by going to " +
                s"$url${routes.AuthController.setNewPassword(email.verificationCode.get)}",
            category = ElectronicMailCategory("reset_password")
          ))
          email
        }
      } match {
        case Some(email) =>
          Ok(Json.obj("success" -> true))
        case None =>
          Ok(Json.obj("error" -> "no_account"))
      }
    } getOrElse BadRequest
  }

  def uploadBinaryPicture() = JsonAction(allowPending = true, parser = parse.temporaryFile)(authenticatedAction = doUploadBinaryPicture(_), unauthenticatedAction = doUploadBinaryPicture(_))
  def doUploadBinaryPicture(implicit request: Request[play.api.libs.Files.TemporaryFile]): Result = {
    request.userOpt.orElse(request.identityOpt) match {
      case Some(_) =>
        s3ImageStore.uploadTemporaryPicture(request.body.file) match {
          case Success((token, pictureUrl)) =>
            Ok(Json.obj("token" -> token, "url" -> pictureUrl))
          case Failure(ex) =>
            airbrakeNotifier.notify(AirbrakeError(ex, Some("Couldn't upload temporary picture (xhr direct)")))
            BadRequest(JsNumber(0))
        }
      case None => Forbidden(JsNumber(0))
    }
  }

  def uploadFormEncodedPicture() = JsonAction(allowPending = true, parser = parse.multipartFormData)(authenticatedAction = doUploadFormEncodedPicture(_), unauthenticatedAction = doUploadFormEncodedPicture(_))
  def doUploadFormEncodedPicture(implicit request: Request[MultipartFormData[play.api.libs.Files.TemporaryFile]]) = {
    request.userOpt.orElse(request.identityOpt) match {
      case Some(_) =>
        request.body.file("picture").map { picture =>
          s3ImageStore.uploadTemporaryPicture(picture.ref.file) match {
            case Success((token, pictureUrl)) =>
              Ok(Json.obj("token" -> token, "url" -> pictureUrl))
            case Failure(ex) =>
              airbrakeNotifier.notify(AirbrakeError(ex, Some("Couldn't upload temporary picture (form encoded)")))
              BadRequest(JsNumber(0))
          }
        } getOrElse {
          BadRequest(JsNumber(0))
        }
      case None => Forbidden(JsNumber(0))
    }
  }

}

package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import com.keepit.common.controller.{AuthenticatedRequest, WebsiteController, ActionAuthenticator}
import com.keepit.common.controller.ActionAuthenticator.FORTYTWO_USER_ID
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.time._
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
import play.api.http.HeaderNames.{LOCATION, REFERER, SET_COOKIE}
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
import com.keepit.commanders.InviteCommander

object AuthController {
  val LinkWithKey = "linkWith"
}

class AuthController @Inject() (
    db: Database,
    clock: Clock,
    userCredRepo: UserCredRepo,
    socialRepo: SocialUserInfoRepo,
    actionAuthenticator: ActionAuthenticator,
    userRepo: UserRepo,
    postOffice: LocalPostOffice,
    userValueRepo: UserValueRepo,
    s3ImageStore: S3ImageStore,
    airbrakeNotifier: AirbrakeNotifier,
    emailAddressRepo: EmailAddressRepo,
    inviteCommander: InviteCommander,
    passwordResetRepo: PasswordResetRepo
  ) extends WebsiteController(actionAuthenticator) with Logging {

  private val PopupKey = "popup"

  // Note: some of the below code is taken from ProviderController in SecureSocial
  // Logout is still handled by SecureSocial directly.

  private implicit val readsOAuth2Info = Json.reads[OAuth2Info]

  private def newSignup()(implicit request: Request[_]) =
    request.cookies.get("QA").isDefined || current.configuration.getBoolean("newSignup").getOrElse(false)

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

  def loginSocial(provider: String) = ProviderController.authenticate(provider)
  def loginWithUserPass(link: String) = Action { implicit request =>
    ProviderController.authenticate("userpass")(request) match {
      case res: SimpleResult[_] if res.header.status == 303 =>
        val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
        val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
        val newSession = if (link != "") {
          // TODO: why is OriginalUrlKey being removed? should we keep it?
          resSession - SecureSocial.OriginalUrlKey + (AuthController.LinkWithKey -> link)
        } else resSession
        Ok(Json.obj("uri" -> res.header.headers.get(LOCATION).get)).withCookies(resCookies: _*).withSession(newSession)
      case res => res
    }
  }

  def afterLogin() = HtmlAction(allowPending = true)(authenticatedAction = { implicit request =>
    if (request.user.state == UserStates.PENDING) {
      Redirect("/")
    } else if (request.user.state == UserStates.INCOMPLETE_SIGNUP) {
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else if (request.kifiInstallationId.isEmpty && !hasSeenInstall) {
      Redirect(com.keepit.controllers.website.routes.HomeController.install())
    } else {
      session.get(SecureSocial.OriginalUrlKey) map { url =>
        Redirect(url).withSession(session - SecureSocial.OriginalUrlKey)
      } getOrElse {
        Redirect("/")
      }
    }
  }, unauthenticatedAction = { implicit request =>
    if (newSignup && request.identityOpt.isDefined) {
      // User tried to log in (not sign up) with social network.
      // A user with this email address exists in the system, but it is not yet linked to this social identity.
      Ok(views.html.auth.connectToAuthenticate(
        emailAddress = request.identityOpt.get.email.get,
        network = SocialNetworkType(request.identityOpt.get.identityId.providerId),
        logInAttempted = true
      ))
    } else {
      Redirect("/") // error??
      // Ok(views.html.website.welcome(msg = request.flash.get("error")))
    }
  })

  def signup(provider: String) = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] =>
        val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
        val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
        // TODO: set FORTYTWO_USER_ID instead of clearing it and then setting it on the next request?
        res.withSession(resSession - FORTYTWO_USER_ID
          + (SecureSocial.OriginalUrlKey -> routes.AuthController.signupPage().url))
      case res => res
    }
  }

  def link(provider: String) = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] =>
        val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
        val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
        if (resSession.get(PopupKey).isDefined) {
          res.withSession(resSession + (SecureSocial.OriginalUrlKey -> routes.AuthController.popupAfterLinkSocial(provider).url))
        } else if (resSession.get(SecureSocial.OriginalUrlKey).isEmpty) {
          request.headers.get(REFERER).map { url =>
            res.withSession(resSession + (SecureSocial.OriginalUrlKey -> url))
          } getOrElse res
        } else res
      case res => res
    }
  }

  def popupBeforeLinkSocial(provider: String) = AuthenticatedHtmlAction(allowPending = true) { implicit request =>
    Ok(views.html.auth.popupBeforeLinkSocial(SocialNetworkType(provider))).withSession(session + (PopupKey -> "1"))
  }

  def popupAfterLinkSocial(provider: String) = AuthenticatedHtmlAction(allowPending = true) { implicit request =>
    def esc(s: String) = s.replaceAll("'", """\\'""")
    val identity = request.identityOpt.get
    Ok(s"<script>try{window.opener.afterSocialLink('${esc(identity.firstName)}','${esc(identity.lastName)}','${esc(identityPicture(identity))}')}finally{window.close()}</script>")
      .withSession(session - PopupKey)
  }

  // --
  // Utility methods
  // --

  private def hasSeenInstall(implicit request: AuthenticatedRequest[_]): Boolean = {
    db.readOnly { implicit s => userValueRepo.getValue(request.userId, "has_seen_install").exists(_.toBoolean) }
  }

  private case class EmailPassword(email: String, password: String)

  // TODO: something different if already logged in?
  def signinPage() = HtmlAction(allowPending = true)(authenticatedAction = doLoginPage(_), unauthenticatedAction = doLoginPage(_))

  // Finalize account
  def signupPage() = HtmlAction(allowPending = true)(authenticatedAction = doSignupPage(_), unauthenticatedAction = doSignupPage(_))


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
                  if (finalized) {
                    val uri = session.get(SecureSocial.OriginalUrlKey).getOrElse(home.url)
                    Ok(Json.obj("uri" -> uri))
                      .withSession(session - SecureSocial.OriginalUrlKey + (FORTYTWO_USER_ID -> sui.userId.get.toString))
                      .withCookies(authenticator.toCookie)
                  } else {
                    Ok(Json.obj("success" -> true)).withSession(session + (FORTYTWO_USER_ID -> sui.userId.get.toString))
                      .withCookies(authenticator.toCookie)
                  }
                }
              )
            } else {
              // emailIsVerifiedOrPrimary lets you know if the email is verified to the user.
              // Deal with later?
              Forbidden(Json.obj("error" -> "user_exists_failed_auth"))
            }
        }.getOrElse {
          val pInfo = hasher.hash(password)
          val (newIdentity, userId) = saveUserPasswordIdentity(None, request.identityOpt, emailAddress, pInfo, isComplete = false)
          Authenticator.create(newIdentity).fold(
            error => Status(INTERNAL_SERVER_ERROR)("0"),
            authenticator =>
              Ok(Json.obj("success"-> true, "email" -> emailAddress, "new_account" -> true))
                .withSession(session + (FORTYTWO_USER_ID -> userId.toString))
                .withCookies(authenticator.toCookie)
          )
        }
      }
    )
  }

  private def doLoginPage(implicit request: Request[_]): Result = {
    Ok(views.html.auth.auth("login"))
  }

  private def doSignupPage(implicit request: Request[_]): Result = {
    def emailAddressMatchesSomeKifiUser(identity: Identity): Boolean = {
      identity.email.flatMap { addr =>
        db.readOnly { implicit s =>
          emailAddressRepo.getByAddressOpt(addr)
        }
      }.isDefined
    }

    (request.userOpt, request.identityOpt) match {
      case (Some(user), _) if user.state != UserStates.INCOMPLETE_SIGNUP =>
        // Complete user, they don't need to be here!
        Redirect(s"${com.keepit.controllers.website.routes.HomeController.home.url}?m=0")
      case (Some(user), Some(identity)) =>
        // User exists, is incomplete
        val (firstName, lastName) = if (identity.firstName.contains("@")) ("","") else (User.sanitizeName(identity.firstName), User.sanitizeName(identity.lastName))
        val picture = identityPicture(identity)
        Ok(views.html.auth.auth(
          view = "signup2Email",
          emailAddress = identity.email.getOrElse(""),
          picturePath = picture,
          firstName = firstName,
          lastName = lastName
        ))
      case (Some(user), None) =>
        // User but no identity. Huh?
        // Haven't run into this one. Redirecting user to logout, ideally to fix their cookie situation
        Redirect(securesocial.controllers.routes.LoginPage.logout)
      case (None, Some(identity)) if emailAddressMatchesSomeKifiUser(identity) =>
        // No user exists, but social network identity’s email address matches a Kifi user
        Ok(views.html.auth.connectToAuthenticate(
          emailAddress = identity.email.get,
          network = SocialNetworkType(identity.identityId.providerId),
          logInAttempted = false
        ))
      case (None, Some(identity)) if request.flash.get("signin_error").exists(_ == "no_account") =>
        // No user exists, social login was attempted. Let user choose what to do next.
        Ok(views.html.auth.loggedInWithWrongNetwork(
          network = SocialNetworkType(identity.identityId.providerId)
        ))
      case (None, Some(identity)) =>
        // No user exists, has social network identity, must finalize
        Ok(views.html.auth.auth(
          view = "signup2Social",
          firstName = User.sanitizeName(identity.firstName),
          lastName = User.sanitizeName(identity.lastName),
          emailAddress = identity.email.getOrElse(""),
          picturePath = identityPicture(identity)
        ))
      case (None, None) =>
        Ok(views.html.auth.auth("signup"))
    }
  }

  private def identityPicture(identity: Identity) = {
    identity.identityId.providerId match {
      case "facebook" =>
        s"//graph.facebook.com/${identity.identityId.userId}/picture?width=200&height=200"
      case _ => identity.avatarUrl.getOrElse(S3UserPictureConfig.defaultImage)
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
      val identity = db.readOnly { implicit session =>
        socialRepo.getByUser(request.userId).find(_.networkType == SocialNetworks.FORTYTWO).flatMap(_.credentials)
      } getOrElse(request.identityOpt.get)
      val pinfo = identity.passwordInfo.get
      val email = identity.email.get
      val (newIdentity, userId) = saveUserPasswordIdentity(request.userIdOpt, request.identityOpt, email = email, passwordInfo = pinfo,
        firstName = firstName, lastName = lastName, isComplete = true)

      val cropAttributes = parseCropForm(picHeight, picWidth, cropX, cropY, cropSize) tap (r => log.info(s"Cropped attributes for ${request.user.id.get}: " + r))
      picToken.map { token =>
        s3ImageStore.copyTempFileToUserPic(request.user.id.get, request.user.externalId, token, cropAttributes)
      }

      inviteCommander.markPendingInvitesAsAccepted(userId, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))

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

        inviteCommander.markPendingInvitesAsAccepted(userId, request.cookies.get("inv").map(v => ExternalId[Invitation](v.name)))

        val emailConfirmedBySocialNetwork = request.identityOpt.flatMap(_.email).exists(_.trim == emailAddress.trim)

        finishSignup(emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork)
    })
  }

  private def finishSignup(newIdentity: Identity, emailConfirmedAlready: Boolean)(implicit request: Request[JsValue]): Result = {
    if (!emailConfirmedAlready) {
      // todo: Move to user controller/commander
      db.readWrite { implicit s =>
        val emailAddrStr = newIdentity.email.get
        val emailAddr = emailAddressRepo.save(
          emailAddressRepo.getByAddressOpt(emailAddrStr).get.withVerificationCode(clock.now))
        val verifyUrl = s"$url${routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"

        postOffice.sendMail(ElectronicMail(
          from = EmailAddresses.NOTIFICATIONS,
          to = Seq(GenericEmailAddress(emailAddrStr)),
          subject = "Kifi.com |  Please confirm your email address",
          htmlBody = views.html.email.verifyEmail(newIdentity.firstName, verifyUrl).body,
          category = ElectronicMailCategory("email_confirmation")
        ))
      }
    }

    val uri = session.get(SecureSocial.OriginalUrlKey).getOrElse("/")

    Authenticator.create(newIdentity).fold(
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => Ok(Json.obj("uri" -> uri)).withNewSession.withCookies(authenticator.toCookie).discardingCookies(DiscardingCookie("inv"))
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
    val fName = User.sanitizeName(if (isComplete || firstName.nonEmpty) firstName else email)
    val lName = User.sanitizeName(lastName)
    val newIdentity = UserIdentity(
      userId = userIdOpt,
      socialUser = SocialUser(
        identityId = IdentityId(email, SocialNetworks.FORTYTWO.authProvider),
        firstName = fName,
        lastName = lName,
        fullName = s"$fName $lName",
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

  def verifyEmail(code: String) = HtmlAction(allowPending = true)(authenticatedAction = doVerifyEmail(code)(_), unauthenticatedAction = requireLoginToVerifyEmail(code)(_))
  def doVerifyEmail(code: String)(implicit request: AuthenticatedRequest[_]): Result = {
    db.readWrite { implicit s =>
      emailAddressRepo.verify(request.userId, code) match {
        case true if request.user.state == UserStates.PENDING =>
          Redirect("/?m=1")
        case true =>
          Redirect("/profile?m=1")
        case _ =>  // TODO: make these links expire and handle "expired" case
          BadRequest(views.html.website.verifyEmailError(error = "invalid_code"))
      }
    }
  }
  def requireLoginToVerifyEmail(code: String)(implicit request: Request[_]): Result = {
    Redirect(routes.AuthController.signinPage())
      .withSession(session + (SecureSocial.OriginalUrlKey -> routes.AuthController.verifyEmail(code).url))
  }

  def forgotPassword() = JsonToJsonAction(allowPending = true)(authenticatedAction = doForgotPassword(_), unauthenticatedAction = doForgotPassword(_))
  def doForgotPassword(implicit request: Request[JsValue]): Result = {
    (request.body \ "email").asOpt[String] map { emailAddrStr =>
        val emailAddressesOpt = db.readOnly { implicit session =>
          getResetEmailAddresses(emailAddrStr)
        }
        emailAddressesOpt match {
          case Some((userId, emailAddresses)) if emailAddresses.nonEmpty =>
            db.readWrite { implicit session =>
              emailAddresses.map { resetEmailAddress =>
                val reset = passwordResetRepo.createNewResetToken(userId, resetEmailAddress)
                val resetUrl = s"$url${routes.AuthController.setPasswordPage(reset.token)}"
                emailAddresses.foreach { emailAddress =>
                  postOffice.sendMail(ElectronicMail(
                    from = EmailAddresses.NOTIFICATIONS,
                    to = Seq(resetEmailAddress),
                    subject = "Kifi.com | Password reset requested",
                    htmlBody = views.html.email.resetPassword(resetUrl).body,
                    category = ElectronicMailCategory("reset_password")
                  ))
                }
              }
            }
            Ok(Json.obj("success" -> true))
          case _ =>
            log.warn(s"Could not reset password because supplied email address $emailAddrStr not found.")
            BadRequest(Json.obj("error" -> "no_account"))
        }
    } getOrElse BadRequest("0")
  }


  def setPasswordPage(code: String) = Action { implicit request =>
    db.readWrite { implicit s =>
      passwordResetRepo.getByToken(code) match {
        case Some(pr) if passwordResetRepo.tokenIsNotExpired(pr) =>
          Ok(views.html.auth.setPassword(code = code))
        case Some(pr) if pr.state == PasswordResetStates.ACTIVE || pr.state == PasswordResetStates.INACTIVE =>
          Ok(views.html.auth.setPassword(error = "expired"))
        case Some(pr) if pr.state == PasswordResetStates.USED =>
          Ok(views.html.auth.setPassword(error = "already_used"))
        case _ =>
          Ok(views.html.auth.setPassword(error = "invalid_code"))
      }
    }
  }

  def setPassword() = JsonToJsonAction(allowPending = true)(authenticatedAction = doSetPassword(_), unauthenticatedAction = doSetPassword(_))
  def doSetPassword(implicit request: Request[JsValue]): Result = {
    (for {
      code <- (request.body \ "code").asOpt[String]
      password <- (request.body \ "password").asOpt[String].filter(_.length >= 7)
    } yield {
      db.readWrite { implicit s =>
        passwordResetRepo.getByToken(code) match {
          case Some(pr) if passwordResetRepo.tokenIsNotExpired(pr) =>
            val email = passwordResetRepo.useResetToken(code, request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress))
            for (sui <- socialRepo.getByUser(pr.userId) if sui.networkType == SocialNetworks.FORTYTWO) {
              UserService.save(UserIdentity(
                userId = sui.userId,
                socialUser = sui.credentials.get.copy(
                  passwordInfo = Some(current.plugin[PasswordHasher].get.hash(password))
                )
              ))
            }
            Ok(Json.obj("uri" -> com.keepit.controllers.website.routes.HomeController.home.url)) // TODO: create session
          case Some(pr) if pr.state == PasswordResetStates.ACTIVE || pr.state == PasswordResetStates.INACTIVE =>
            Ok(Json.obj("error" -> "expired"))
          case Some(pr) if pr.state == PasswordResetStates.USED =>
            Ok(Json.obj("error" -> "already_used"))
          case _ =>
            Ok(Json.obj("error" -> "invalid_code"))
        }
      }
    }) getOrElse BadRequest("0")
  }


  private def getResetEmailAddresses(emailAddrStr: String): Option[(Id[User], Set[EmailAddressHolder])] = {
    def emailToEmailHolder(em: EmailAddress) = new EmailAddressHolder {
      val address: String = em.address
    }

    db.readOnly { implicit s =>
      val emailAddresses: Option[(Id[User], Set[EmailAddressHolder])] = emailAddressRepo.getByAddressOpt(emailAddrStr, excludeState = None).flatMap { emailAddress =>
        val user = userRepo.get(emailAddress.userId)
        val primaryEmail = user.primaryEmailId.map(emailAddressRepo.get).map(Set(_)).getOrElse(Set.empty)

        if (emailAddress.state == EmailAddressStates.VERIFIED) {
          Some((user.id.get, (Set(emailAddress) ++ primaryEmail).map(emailToEmailHolder)))
        } else {
          val allUserEmailAddresses = emailAddressRepo.getByUser(emailAddress.userId)
          val _addrs = allUserEmailAddresses.filter(em => em.state == EmailAddressStates.VERIFIED).toSet ++ primaryEmail
          if (_addrs.isEmpty) {
            None // we could also send to the oldest email address on file
          } else Some((user.id.get, _addrs.map(emailToEmailHolder)))
        }
      }
      emailAddresses.orElse {
        socialRepo.getOpt(SocialId(emailAddrStr), SocialNetworks.FORTYTWO).collect { case sui if sui.userId.isDefined =>
          (sui.userId.get, Set(new EmailAddressHolder { val address: String = emailAddrStr }))
        }
      }
    }
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

  def cancelAuth() = JsonAction(allowPending = true)(authenticatedAction = doCancelPage(_), unauthenticatedAction = doCancelPage(_))
  private def doCancelPage(implicit request: Request[_]): Result = {
    // todo(Andrew): Remove from database: user, credentials, securesocial session
    Ok("1").withNewSession.discardingCookies(
      DiscardingCookie(Authenticator.cookieName, Authenticator.cookiePath, Authenticator.cookieDomain, Authenticator.cookieSecure))
  }

}

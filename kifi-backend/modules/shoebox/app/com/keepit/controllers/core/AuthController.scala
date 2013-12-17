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

import play.api.Play._
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json.{JsNumber, JsValue, Json}
import play.api.mvc._
import securesocial.controllers.ProviderController
import securesocial.core._
import securesocial.core.providers.utils.{PasswordHasher, GravatarHelper}
import play.api.libs.iteratee.Enumerator
import play.api.Play
import com.keepit.common.store.{S3UserPictureConfig, S3ImageStore}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.commanders.InviteCommander

object AuthController {
  val LinkWithKey = "linkWith"

  private lazy val obscureRegex = """^(?:[^@]|([^@])[^@]+)@""".r
  def obscureEmailAddress(address: String) = obscureRegex.replaceFirstIn(address, """$1...@""")
}

class AuthController @Inject() (
    db: Database,
    clock: Clock,
    authCommander: AuthCommander,
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
    passwordResetRepo: PasswordResetRepo,
    kifiInstallationRepo: KifiInstallationRepo
  ) extends WebsiteController(actionAuthenticator) with Logging {

  private val PopupKey = "popup"

  // Note: some of the below code is taken from ProviderController in SecureSocial
  // Logout is still handled by SecureSocial directly.

  def loginSocial(provider: String) = ProviderController.authenticate(provider)
  def logInWithUserPass(link: String) = Action { implicit request =>
    ProviderController.authenticate("userpass")(request) match {
      case res: SimpleResult[_] if res.header.status == 303 =>
        authCommander.authHandler(request, res) { (cookies:Seq[Cookie], sess:Session) =>
          val newSession = if (link != "") {
            sess - SecureSocial.OriginalUrlKey + (AuthController.LinkWithKey -> link) // removal of OriginalUrlKey might be redundant
          } else sess
          Ok(Json.obj("uri" -> res.header.headers.get(LOCATION).get)).withCookies(cookies: _*).withSession(newSession)
        }
      case res => res
    }
  }

  def afterLogin() = HtmlAction(allowPending = true)(authenticatedAction = { implicit request =>
    if (request.user.state == UserStates.PENDING) {
      inviteCommander.markPendingInvitesAsAccepted(request.user.id.get, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))
      Redirect("/")
    } else if (request.user.state == UserStates.INCOMPLETE_SIGNUP) {
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else if (request.kifiInstallationId.isEmpty && !hasSeenInstall) {
      inviteCommander.markPendingInvitesAsAccepted(request.user.id.get, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))
      Redirect(com.keepit.controllers.website.routes.HomeController.install())
    } else {
      session.get(SecureSocial.OriginalUrlKey) map { url =>
        Redirect(url).withSession(session - SecureSocial.OriginalUrlKey)
      } getOrElse {
        Redirect("/")
      }
    }
  }, unauthenticatedAction = { implicit request =>
    if (request.identityOpt.isDefined) {
      // User tried to log in (not sign up) with social network.
      request.identityOpt.get.email.flatMap(e => db.readOnly(emailAddressRepo.getByAddressOpt(e)(_))) match {
        case Some(addr) =>
          // A user with this email address exists in the system, but it is not yet linked to this social identity.
          Ok(views.html.auth.connectToAuthenticate(
            emailAddress = request.identityOpt.get.email.get,
            network = SocialNetworkType(request.identityOpt.get.identityId.providerId),
            logInAttempted = true
          ))
        case None =>
          // No email for this user exists in the system.
          Redirect("/signup")
      }
    } else {
      Redirect("/") // error??
      // Ok(views.html.website.welcome(msg = request.flash.get("error")))
    }
  })

  def signup(provider: String) = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] =>
        authCommander.authHandler(request, res) { (_, sess:Session) =>
          // TODO: set FORTYTWO_USER_ID instead of clearing it and then setting it on the next request?
          res.withSession(sess - FORTYTWO_USER_ID + (SecureSocial.OriginalUrlKey -> routes.AuthController.signupPage().url))
        }
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

  def loginPage() = HtmlAction(allowPending = true)(authenticatedAction = { request =>
    Redirect("/")
  }, unauthenticatedAction = { request =>
    Ok(views.html.auth.auth("login"))
  })

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
                    Ok(Json.obj("uri" -> session.get(SecureSocial.OriginalUrlKey).getOrElse(home.url).asInstanceOf[String]))
                      .withSession(session - SecureSocial.OriginalUrlKey + (FORTYTWO_USER_ID -> sui.userId.get.toString))
                      .withCookies(authenticator.toCookie)
                  } else {
                    Ok(Json.obj("success" -> true))
                      .withSession(session + (FORTYTWO_USER_ID -> sui.userId.get.toString))
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
              Ok(Json.obj("success" -> true))
                .withSession(session + (FORTYTWO_USER_ID -> userId.toString))
                .withCookies(authenticator.toCookie)
          )
        }
      }
    )
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
          picturePath = identityPicture(identity),
          network = Some(SocialNetworkType(identity.identityId.providerId))
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
  def userPassFinalizeAccountAction() = JsonToJsonAction(allowPending = true)(authenticatedAction = authCommander.doUserPassFinalizeAccountAction(_), unauthenticatedAction = _ => Forbidden(JsNumber(0)))

  // social finalize action (new)
  def socialFinalizeAccountAction() = JsonToJsonAction(allowPending = true)(authenticatedAction = authCommander.doSocialFinalizeAccountAction(_), unauthenticatedAction = authCommander.doSocialFinalizeAccountAction(_))

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

  def verifyEmail(code: String) = HtmlAction(allowPending = true)(authenticatedAction = doVerifyEmail(code)(_), unauthenticatedAction = doVerifyEmail(code)(_))
  def doVerifyEmail(code: String)(implicit request: MaybeAuthenticatedRequest): Result = {
    db.readWrite { implicit s =>
      emailAddressRepo.getByCode(code).map { address =>
        val user = userRepo.get(address.userId)
        val verification = emailAddressRepo.verify(address.userId, code)

        if (verification._2) { // code is being used for the first time
          if (user.primaryEmailId.isEmpty) {
            userRepo.save(user.copy(primaryEmailId = Some(address.id.get)))
            userValueRepo.clearValue(user.id.get, "pending_primary_email")
          } else {
            val pendingEmail = userValueRepo.getValue(user.id.get, "pending_primary_email")
            if (pendingEmail.isDefined && address.address == pendingEmail.get) {
              userValueRepo.clearValue(user.id.get, "pending_primary_email")
              userRepo.save(user.copy(primaryEmailId = Some(address.id.get)))
            }
          }
        }

        verification match {
          case (true, _) if user.state == UserStates.PENDING =>
            Redirect("/?m=1")
          case (true, true) if request.userIdOpt.isEmpty || (request.userIdOpt.isDefined && request.userIdOpt.get.id == user.id.get.id) =>
            // first time being used, not logged in OR logged in as correct user
            authenticateUser(user.id.get,
              error => throw error,
              authenticator => {
                val resp = if (kifiInstallationRepo.all(user.id.get, Some(KifiInstallationStates.INACTIVE)).isEmpty) {
                  // user has no installations
                  Redirect("/install")
                } else {
                  Redirect("/profile?m=1")
                }
                resp.withSession(request.request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                  .withCookies(authenticator.toCookie)
              }
            )
          case (true, _) =>
            Ok(views.html.website.verifyEmailThanks(address.address, user.firstName))
        }
      }.getOrElse {
        BadRequest(views.html.website.verifyEmailError(error = "invalid_code"))
      }
    }
  }
  def requireLoginToVerifyEmail(code: String)(implicit request: Request[_]): Result = {
    Redirect(routes.AuthController.loginPage())
      .withSession(session + (SecureSocial.OriginalUrlKey -> routes.AuthController.verifyEmail(code).url))
  }
  private def authenticateUser[T](userId: Id[User], onError: Error => T, onSuccess: Authenticator => T) = {
    val identity = db.readOnly { implicit session =>
      val suis = socialRepo.getByUser(userId)
      val sui = socialRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO).getOrElse(suis.head)
      sui.credentials.get
    }
    Authenticator.create(identity).fold(onError, onSuccess)
  }

  def forgotPassword() = JsonToJsonAction(allowPending = true)(authenticatedAction = doForgotPassword(_), unauthenticatedAction = doForgotPassword(_))
  def doForgotPassword(implicit request: Request[JsValue]): Result = {
    (request.body \ "email").asOpt[String] map { emailAddrStr =>
        db.readOnly { implicit session =>
          getResetEmailAddresses(emailAddrStr)
        } match {
          case Some((userId, verifiedEmailAddressOpt)) =>
            val emailAddresses = Set(GenericEmailAddress(emailAddrStr)) ++ verifiedEmailAddressOpt
            db.readWrite { implicit session =>
              emailAddresses.map { resetEmailAddress =>
                // TODO: Invalidate both reset tokens the first time one is used.
                val reset = passwordResetRepo.createNewResetToken(userId, resetEmailAddress)
                val resetUrl = s"$url${routes.AuthController.setPasswordPage(reset.token)}"
                postOffice.sendMail(ElectronicMail(
                  from = EmailAddresses.NOTIFICATIONS,
                  to = Seq(resetEmailAddress),
                  subject = "Kifi.com | Password reset requested",
                  htmlBody = views.html.email.resetPassword(resetUrl).body,
                  category = PostOffice.Categories.User.RESET_PASSWORD
                ))
              }
            }
            Ok(Json.obj("addresses" -> emailAddresses.map { email =>
              if (email.address == emailAddrStr) emailAddrStr else AuthController.obscureEmailAddress(email.address)
            }))
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

  private def getResetEmailAddresses(emailAddrStr: String): Option[(Id[User], Option[EmailAddressHolder])] = {
    db.readOnly { implicit s =>
      val emailAddrOpt = emailAddressRepo.getByAddressOpt(emailAddrStr, excludeState = None)  // TODO: exclude INACTIVE records
      emailAddrOpt.map(_.userId) orElse socialRepo.getOpt(SocialId(emailAddrStr), SocialNetworks.FORTYTWO).flatMap(_.userId) map { userId =>
        emailAddrOpt.filter(_.verified) map { _ =>
          (userId, None)
        } getOrElse {
          // TODO: use user's primary email address once hooked up
          (userId, emailAddressRepo.getAllByUser(userId).find(_.verified))
        }
      }
    }
  }

  def uploadBinaryPicture() = JsonAction(allowPending = true, parser = parse.temporaryFile)(authenticatedAction = authCommander.doUploadBinaryPicture(_), unauthenticatedAction = authCommander.doUploadBinaryPicture(_))

  def uploadFormEncodedPicture() = JsonAction(allowPending = true, parser = parse.multipartFormData)(authenticatedAction = authCommander.doUploadFormEncodedPicture(_), unauthenticatedAction = authCommander.doUploadFormEncodedPicture(_))

  def cancelAuth() = JsonAction(allowPending = true)(authenticatedAction = doCancelPage(_), unauthenticatedAction = doCancelPage(_))
  private def doCancelPage(implicit request: Request[_]): Result = {
    // todo(Andrew): Remove from database: user, credentials, securesocial session
    Ok("1").withNewSession.discardingCookies(
      DiscardingCookie(Authenticator.cookieName, Authenticator.cookiePath, Authenticator.cookieDomain, Authenticator.cookieSecure))
  }

}
